package local.gsb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppServiceTest {
    @TempDir
    Path dataDirectory;

    private AppService service() {
        return new AppService(dataDirectory, Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));
    }

    private void waitReady(AppService service) throws InterruptedException {
        service.waitForJobs(2000);
    }

    @Test
    void computesIntervalsDirectAndTransitiveEdges() throws Exception {
        AppService service = service();
        service.batch(Map.of("idempotencyKey", "k1", "commands", List.of(
                createContext("A", "Layer A"),
                createContext("B", "Layer B"),
                createContext("C", "Layer C"),
                relation("r1", "A", "earlier-than", "B", "strong"),
                relation("r2", "B", "earlier-than", "C", "normal"),
                dating("d1", "A", 100L, 200L),
                dating("d2", "C", 150L, 300L)
        )));
        waitReady(service);

        Map<String, Object> view = service.branchView("main");
        assertEquals("feasible", view.get("status"));
        assertEquals(List.of("A", "B", "C"), view.get("stableTopologicalOrder"));
        assertEquals(2, view.get("directEdgeCount"));
        assertEquals(1, view.get("transitiveEdgeCount"));
        assertTrue(Json.write(view).contains("\"kind\":\"transitive\""));
        assertTrue(Json.write(view).contains("\"pathEvidenceIds\":[\"r1\",\"r2\"]"));
    }

    @Test
    void reportsMinimalEvidenceSetForCycle() throws Exception {
        AppService service = service();
        service.batch(Map.of("idempotencyKey", "cycle-setup", "commands", List.of(
                createContext("A", "A"), createContext("B", "B"), createContext("C", "C"),
                relation("r1", "A", "earlier-than", "B", "strong"),
                relation("r2", "B", "earlier-than", "C", "normal"),
                relation("unrelated", "A", "contemporaneous", "A", "weak")
        )));
        waitReady(service);
        service.batch(Map.of("idempotencyKey", "cycle-close", "commands", List.of(
                relation("r3", "C", "earlier-than", "A", "strong")
        )));
        waitReady(service);

        Map<String, Object> view = service.branchView("main");
        assertEquals("contradiction", view.get("status"));
        Map<String, Object> contradiction = Json.object(view.get("contradiction"));
        assertEquals("topological-cycle", contradiction.get("type"));
        assertEquals(List.of(List.of("r1", "r2", "r3")),
                contradiction.get("minimalEvidenceSets"));
    }

    @Test
    void normalizesOpenEndpointsAcrossBceAndZeroAndFindsDateConflict() throws Exception {
        AppService service = service();
        service.batch(Map.of("idempotencyKey", "date-base", "commands", List.of(
                createContext("A", "A"), createContext("B", "B"),
                datingOpen("d1", "A", -100L, 0L, false, true),
                datingOpen("d2", "B", 0L, 100L, true, false),
                relation("r", "A", "earlier-than", "B", "strong")
        )));
        waitReady(service);
        Map<String, Object> view = service.branchView("main");
        assertEquals("feasible", view.get("status"));
        String intervals = Json.write(view.get("components"));
        assertTrue(intervals.contains("\"lower\":-100"));
        assertTrue(intervals.contains("\"upper\":-1"));
        assertTrue(intervals.contains("\"lower\":1"));
        assertTrue(intervals.contains("\"upper\":100"));

        service.batch(Map.of("idempotencyKey", "date-conflict", "commands", List.of(
                datingOpen("d3", "A", 50L, 60L, false, false)
        )));
        waitReady(service);
        Map<String, Object> contradiction = Json.object(service.branchView("main").get("contradiction"));
        assertEquals("empty-interval", contradiction.get("type"));
        assertEquals(List.of(List.of("d1", "d3")), contradiction.get("minimalEvidenceSets"));
    }

    @Test
    void retractOneEvidenceDoesNotRemoveParallelEvidence() throws Exception {
        AppService service = service();
        service.batch(Map.of("idempotencyKey", "parallel", "commands", List.of(
                createContext("A", "A"), createContext("B", "B"),
                relation("weak-r", "A", "earlier-than", "B", "weak"),
                relation("strong-r", "A", "earlier-than", "B", "strong")
        )));
        waitReady(service);
        service.batch(Map.of("idempotencyKey", "retract-weak", "commands", List.of(
                Map.of("type", "retract-relation", "branchId", "main", "evidenceId", "weak-r", "reason", "re-read")
        )));
        waitReady(service);

        Map<String, Object> view = service.branchView("main");
        assertEquals("feasible", view.get("status"));
        assertTrue(Json.write(view.get("relationEvidence")).contains("strong-r"));
        assertFalse(Json.write(view.get("relationEvidence")).contains("weak-r"));
        assertEquals(List.of("weak-r"), view.get("retractedRelationIds"));
    }

    @Test
    void batchIsAtomicAndIdempotent() throws Exception {
        AppService service = service();
        Map<String, Object> request = Map.of("idempotencyKey", "atomic", "commands", List.of(
                createContext("Only", "Only"),
                Map.of("type", "add-relation",
                        "id", "bad",
                        "branchId", "main",
                        "fromContextId", "Only",
                        "toContextId", "Missing",
                        "relation", "earlier-than",
                        "source", "book")
        ));
        assertThrows(IllegalArgumentException.class, () -> service.batch(request));
        assertThrows(IllegalArgumentException.class, () -> service.batch(request));

        Map<String, Object> view = service.branchView("main");
        assertEquals(List.of(), view.get("contexts"));
        assertEquals(0L, service.stateView().get("seq"));

        service.batch(Map.of("idempotencyKey", "idempotent", "commands", List.of(createContext("A", "A"))));
        Map<String, Object> first = service.batch(Map.of("idempotencyKey", "same", "commands",
                List.of(createContext("B", "B"))));
        waitReady(service);
        Map<String, Object> second = service.batch(Map.of("idempotencyKey", "same", "commands",
                List.of(createContext("C", "C"))));
        assertEquals(first, second);
        Map<String, Object> viewAfter = service.branchView("main");
        assertTrue(Json.write(viewAfter).contains("\"id\":\"B\""));
        assertFalse(Json.write(viewAfter).contains("\"id\":\"C\""));
    }

    @Test
    void hypothesesSnapshotAndMergeRequireManualResolution() throws Exception {
        AppService service = service();
        service.batch(Map.of("idempotencyKey", "base", "commands", List.of(
                createContext("A", "A"), createContext("B", "B"),
                relation("e1", "A", "earlier-than", "B", "normal"),
                dating("d1", "A", -100L, 0L)
        )));
        waitReady(service);
        service.batch(Map.of("idempotencyKey", "snapshot", "commands", List.of(
                Map.of("type", "create-snapshot", "branchId", "main", "id", "pub1", "name", "Published", "note", "ok")
        )));
        service.batch(Map.of("idempotencyKey", "branch", "commands", List.of(
                Map.of("type", "create-branch", "id", "h1", "name", "Alternative",
                        "parentBranchId", "main", "sourceSnapshotId", "pub1")
        )));
        waitReady(service);
        service.batch(Map.of("idempotencyKey", "alternative", "commands", List.of(
                Map.of("type", "retract-relation", "branchId", "h1", "evidenceId", "e1", "reason", "ambiguous"),
                relationForBranch("h1", "e1-alt", "e1", "A", "contemporaneous", "B", "weak")
        )));
        waitReady(service);

        Map<String, Object> preview = service.mergePreview(Map.of("sourceBranchId", "h1", "targetBranchId", "main"));
        assertEquals(1, Json.list(preview.get("conflicts")).size());
        assertThrows(IllegalArgumentException.class, () -> service.batch(Map.of(
                "idempotencyKey", "merge-without-choice",
                "commands", List.of(Map.of("type", "merge-branch", "sourceBranchId", "h1", "targetBranchId", "main")))));
        service.batch(Map.of("idempotencyKey", "merge", "commands", List.of(Map.of(
                "type", "merge-branch",
                "sourceBranchId", "h1",
                "targetBranchId", "main",
                "reason", "reviewer selected contemporaneity",
                "resolutions", List.of(Map.of("evidenceKey", "e1", "choice", "source",
                        "reason", "photograph supports contact"))))));
        waitReady(service);

        Map<String, Object> snapshotView = service.snapshotView("pub1");
        assertEquals(true, snapshotView.get("frozen"));
        assertTrue(Json.write(snapshotView).contains("earlier-than"));
        Map<String, Object> mainView = service.branchView("main");
        assertTrue(Json.write(mainView).contains("contemporaneous"));
    }

    @Test
    void restartRecoversAndProducesStableDigest() throws Exception {
        AppService service = service();
        service.batch(Map.of("idempotencyKey", "digest-base", "commands", List.of(
                createContext("B", "B"), createContext("A", "A"),
                relation("r", "A", "earlier-than", "B", "strong")
        )));
        waitReady(service);
        Map<String, Object> first = service.export("main");

        AppService reopened = new AppService(dataDirectory,
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));
        reopened.recover();
        waitReady(reopened);
        Map<String, Object> second = reopened.export("main");
        assertEquals(first.get("digest"), second.get("digest"));
        assertTrue(String.valueOf(first.get("digest")).startsWith("sha256:"));
    }

    private static Map<String, Object> createContext(String id, String label) {
        return Map.of("type", "create-context", "branchId", "main", "id", id, "label", label,
                "source", "fixture");
    }

    private static Map<String, Object> relation(String id, String from, String relation, String to, String strength) {
        return relationForBranch("main", id, from, relation, to, strength);
    }

    private static Map<String, Object> relationForBranch(String branch, String id, String from, String relation, String to, String strength) {
        return relationForBranch(branch, id, id, from, relation, to, strength);
    }

    private static Map<String, Object> relationForBranch(String branch, String id, String evidenceKey,
                                                         String from, String relation, String to, String strength) {
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("type", "add-relation");
        command.put("id", id);
        command.put("evidenceKey", evidenceKey);
        command.put("branchId", branch);
        command.put("fromContextId", from);
        command.put("toContextId", to);
        command.put("relation", relation);
        command.put("source", "Field Report");
        command.put("pages", "12");
        command.put("strength", strength);
        return command;
    }

    private static Map<String, Object> dating(String id, String context, Long lower, Long upper) {
        return new java.util.LinkedHashMap<>(Map.of("type", "add-dating", "id", id, "evidenceKey", id, "branchId", "main",
                "contextId", context, "source", "Lab", "pages", "9")) {{
            put("lower", lower);
            put("upper", upper);
            put("lowerOpen", false);
            put("upperOpen", false);
        }};
    }

    private static Map<String, Object> datingOpen(String id, String context, Long lower, Long upper,
                                                  boolean lowerOpen, boolean upperOpen) {
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("type", "add-dating");
        command.put("id", id);
        command.put("evidenceKey", id);
        command.put("branchId", "main");
        command.put("contextId", context);
        command.put("lower", lower);
        command.put("upper", upper);
        command.put("lowerOpen", lowerOpen);
        command.put("upperOpen", upperOpen);
        command.put("source", "Era Lab");
        command.put("pages", "7");
        return command;
    }
}
