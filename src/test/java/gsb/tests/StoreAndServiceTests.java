package gsb.tests;

import gsb.model.Json;
import gsb.store.Store;
import gsb.web.ApiException;
import gsb.web.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class StoreAndServiceTests {
    private Path directory;

    public void setup() throws Exception {
        directory = Files.createTempDirectory("gsb-test-");
    }

    @TestMarker
    public void testBatchIsAtomicOnConflict() {
        Service service = new Service(new Store(directory));
        service.batch("main", Map.of("idempotencyKey", "k1", "operations", List.of(
                Map.of("op", "addContext", "context", Map.of("id", "A")),
                Map.of("op", "addContext", "context", Map.of("id", "B")))));
        try {
            service.batch("main", Map.of("idempotencyKey", "k2", "operations", List.of(
                    Map.of("op", "addEvidence", "evidence", Map.of("source", "A", "target", "B", "relation", "earlier-than", "strength", 3, "page", "9")),
                    Map.of("op", "addEvidence", "evidence", Map.of("source", "B", "target", "A", "relation", "earlier-than", "strength", 4, "page", "10")))));
            throw new AssertionError("conflicting batch committed");
        } catch (ApiException expected) {
            Asserts.assertEquals(422, expected.status(), "conflict status");
        }
        Service reloaded = new Service(new Store(directory));
        Map<String, Object> branch = reloaded.branchJson("main", false);
        Map<String, Object> state = Json.object(branch.get("state"));
        Asserts.assertEquals(0, Json.array(state.get("evidences")).size(), "no evidence survives rollback");
        Asserts.assertEquals(2, Json.array(state.get("contexts")).size(), "prior commit remains");
        service.close();
        reloaded.close();
    }

    @TestMarker
    public void testParallelEvidenceRetractionIsIndependent() {
        Service service = new Service(new Store(directory));
        seedTwoContexts(service);
        service.batch("main", Map.of("idempotencyKey", "e", "operations", List.of(
                Map.of("op", "addEvidence", "evidence", Map.of("id", "eA", "source", "A", "target", "B", "relation", "earlier-than", "strength", 2)),
                Map.of("op", "addEvidence", "evidence", Map.of("id", "eB", "source", "A", "target", "B", "relation", "earlier-than", "strength", 5)))));
        service.batch("main", Map.of("idempotencyKey", "r", "operations", List.of(Map.of("op", "retract", "id", "eA", "reason", "ambiguous"))));
        Map<String, Object> branch = service.branchJson("main", true);
        String state = Json.canonical(branch.get("state"));
        Asserts.assertContains(state, "\"id\":\"eA\"");
        Asserts.assertContains(state, "\"retracted\":true");
        Asserts.assertContains(state, "\"id\":\"eB\"");
        String inference = Json.canonical(branch.get("inference"));
        Asserts.assertContains(inference, "\"basis\":\"direct\"");
        service.close();
    }

    @TestMarker
    public void testIdempotentRetryReturnsSameTransaction() {
        Service service = new Service(new Store(directory));
        seedTwoContexts(service);
        Map<String, Object> request = Map.of("idempotencyKey", "same", "operations", List.of(
                Map.of("op", "addEvidence", "evidence", Map.of("id", "only", "source", "A", "target", "B", "relation", "earlier-than", "strength", 3))));
        Map<String, Object> first = service.batch("main", request);
        Map<String, Object> second = service.batch("main", request);
        Asserts.assertEquals(first.get("transactionId"), second.get("transactionId"), "same transaction");
        Service reloaded = new Service(new Store(directory));
        Asserts.assertEquals(1, Json.array(Json.object(reloaded.branchJson("main", false).get("state")).get("evidences")).size(), "one evidence");
        service.close();
        reloaded.close();
    }

    @TestMarker
    public void testBackgroundJobCompletesAndReplaysOnce() throws Exception {
        Service service = new Service(new Store(directory));
        seedTwoContexts(service);
        Map<String, Object> started = service.startJob("main", Map.of("idempotencyKey", "job-key"));
        String jobId = String.valueOf(started.get("jobId"));
        for (int i = 0; i < 20 && !"completed".equals(service.job(jobId).get("status")); i++) Thread.sleep(50);
        Asserts.assertEquals("completed", service.job(jobId).get("status"), "job completes");
        Service reloaded = new Service(new Store(directory));
        for (int i = 0; i < 20 && !"completed".equals(reloaded.job(jobId).get("status")); i++) Thread.sleep(50);
        long completions = java.nio.file.Files.readAllLines(directory.resolve("events.log")).stream()
                .filter(line -> line.contains("JOB_COMPLETED") && line.contains(jobId)).count();
        Asserts.assertEquals(1L, completions, "one completion record");
        service.close();
        reloaded.close();
    }

    @TestMarker
    public void testPublishedSnapshotIsFrozenForBranchAndStableExport() {
        Service service = new Service(new Store(directory));
        seedTwoContexts(service);
        service.publish("main", Map.of("id", "snap-base", "label", "base"));
        Map<String, Object> branch = service.createBranch(Map.of("id", "hyp", "name", "hypothesis"));
        Map<String, Object> info = Json.object(branch.get("info"));
        Asserts.assertEquals("snap-base", info.get("baseSnapshotId"), "branch base snapshot");
        service.batch("hyp", Map.of("idempotencyKey", "branch-add", "operations", List.of(
                Map.of("op", "addEvidence", "evidence", Map.of("id", "branchOnly", "source", "A", "target", "B", "relation", "contemporaneous", "strength", 2)))));
        String mainExport = Json.canonical(service.export("main"));
        Asserts.assertTrue(!mainExport.contains("branchOnly"), "main stays frozen relative to unpublished branch work");
        String a = Json.canonical(service.export("hyp"));
        String b = Json.canonical(service.export("hyp"));
        Asserts.assertEquals(a, b, "deterministic export");
        service.close();
    }

    @TestMarker
    public void testMergeRequiresManualReasonForConflictingInterpretation() {
        Service service = new Service(new Store(directory));
        seedTwoContexts(service);
        service.batch("main", Map.of("idempotencyKey", "shared", "operations", List.of(
                Map.of("op", "addEvidence", "evidence", Map.of("id", "shared", "source", "A", "target", "B", "relation", "earlier-than", "strength", 3)))));
        service.publish("main", Map.of("id", "base", "label", "base"));
        service.createBranch(Map.of("id", "hyp", "name", "hyp"));
        service.batch("hyp", Map.of("idempotencyKey", "hyp-change", "operations", List.of(
                Map.of("op", "interpretEvidence", "id", "shared", "relation", "contemporaneous"))));
        service.batch("main", Map.of("idempotencyKey", "main-note", "operations", List.of(
                Map.of("op", "interpretEvidence", "id", "shared", "relation", "seals"))));
        try {
            service.merge("hyp", Map.of());
            throw new AssertionError("merge without resolution succeeded");
        } catch (ApiException expected) {
            Asserts.assertEquals(409, expected.status(), "manual merge conflict");
        }
        service.merge("hyp", Map.of("resolutions", Map.of("evidences", Map.of("shared", "source"), "reasons", Map.of("shared", "field note supports overlap"))));
        String merged = Json.canonical(service.export("main"));
        Asserts.assertContains(merged, "contemporaneous");
        service.close();
    }

    private void seedTwoContexts(Service service) {
        service.batch("main", Map.of("idempotencyKey", "seed-" + directory, "operations", List.of(
                Map.of("op", "addContext", "context", Map.of("id", "A")),
                Map.of("op", "addContext", "context", Map.of("id", "B")))));
    }

    private @interface TestMarker {}
}
