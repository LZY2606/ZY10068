package gsb.tests;

import gsb.infer.InferenceEngine;
import gsb.infer.InferenceResult;
import gsb.infer.InferenceView;
import gsb.model.BranchState;
import gsb.model.ContextInfo;
import gsb.model.Dating;
import gsb.model.Evidence;
import gsb.model.Interval;
import gsb.model.Json;
import gsb.model.YearPoint;

import java.util.List;
import java.util.Map;

public class CoreTests {
    public void setup() {}

    @TestMarker
    public void testYearZeroIsRejected() {
        try {
            YearPoint.of(0);
            throw new AssertionError("Year zero accepted");
        } catch (IllegalArgumentException expected) {
            Asserts.assertContains(expected.getMessage(), "Year zero");
        }
        Asserts.assertEquals(-2L, YearPoint.fromInput("BCE", 2), "BCE coordinate");
        Asserts.assertEquals(2L, YearPoint.fromInput("CE", 2), "CE coordinate");
    }

    @TestMarker
    public void testOpenEndpointsAcrossZeroBecomeEmpty() {
        try {
            new Interval(-1, true, 1, true);
            throw new AssertionError("Open zero-crossing interval accepted");
        } catch (IllegalArgumentException expected) {
            Asserts.assertContains(expected.getMessage(), "zero");
        }
    }

    @TestMarker
    public void testFeasibleOrderAndIntervals() {
        BranchState state = new BranchState();
        addContext(state, "A");
        addContext(state, "B");
        state.evidences.put("e1", new Evidence("e1", "A", "B", gsb.model.RelationType.EARLIER_THAN, 4, "book", "12", "", 1, false, null, null));
        state.datings.put("d1", Dating.parse(Map.of("context", "A", "interval", Map.of("lower", 100L, "upper", 110L)), "d1", 1));
        state.datings.put("d2", Dating.parse(Map.of("context", "B", "interval", Map.of("lower", 140L, "upper", 160L)), "d2", 1));
        InferenceResult result = new InferenceEngine().analyze(InferenceView.of(state));
        Map<String, Object> json = Json.object(Json.parse(Json.canonical(result.toJson())));
        Asserts.assertEquals(true, json.get("feasible"), "feasible");
        Asserts.assertEquals(List.of("A", "B"), json.get("topologicalOrder"), "topological order");
        String text = Json.canonical(json);
        Asserts.assertContains(text, "\"basis\":\"direct\"");
    }

    @TestMarker
    public void testTransitiveEdgeIsLabelled() {
        BranchState state = new BranchState();
        addContext(state, "A");
        addContext(state, "B");
        addContext(state, "C");
        state.evidences.put("e1", evidence("e1", "A", "B"));
        state.evidences.put("e2", evidence("e2", "B", "C"));
        InferenceResult result = new InferenceEngine().analyze(InferenceView.of(state));
        String text = Json.canonical(result.toJson());
        Asserts.assertContains(text, "\"from\":\"A\"");
        Asserts.assertContains(text, "\"to\":\"C\"");
        Asserts.assertContains(text, "\"basis\":\"transitive\"");
        Asserts.assertContains(text, "\"transitivePath\"");
    }

    @TestMarker
    public void testCycleReportsMinimalEvidenceSet() {
        BranchState state = new BranchState();
        addContext(state, "A");
        addContext(state, "B");
        state.evidences.put("weak", evidence("weak", "A", "B"));
        state.evidences.put("strong", new Evidence("strong", "B", "A", gsb.model.RelationType.EARLIER_THAN, 5, "book", "1", "", 1, false, null, null));
        InferenceResult result = new InferenceEngine().analyze(InferenceView.of(state));
        Map<String, Object> json = result.toJson();
        Asserts.assertEquals(false, json.get("feasible"), "cycle infeasible");
        Map<String, Object> conflict = Json.object(json.get("conflict"));
        List<Object> ids = Json.array(conflict.get("evidenceIds"));
        Asserts.assertEquals(2, ids.size(), "both cycle evidences are necessary");
    }

    private void addContext(BranchState state, String id) {
        state.contexts.put(id, new ContextInfo(id, id, "", 1));
    }

    private Evidence evidence(String id, String source, String target) {
        return new Evidence(id, source, target, gsb.model.RelationType.EARLIER_THAN, 3, "book", "1", "", 1, false, null, null);
    }

    private @interface TestMarker {}
}
