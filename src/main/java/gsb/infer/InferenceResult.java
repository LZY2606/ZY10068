package gsb.infer;

import gsb.model.Conflict;
import gsb.model.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InferenceResult {
    private boolean feasible;
    private Conflict conflict;
    private final List<Map<String, Object>> intervals = new ArrayList<>();
    private final List<Map<String, Object>> edges = new ArrayList<>();
    private final List<List<String>> contemporaneousGroups = new ArrayList<>();
    private List<String> topologicalOrder = new ArrayList<>();
    private final List<Map<String, Object>> constraints = new ArrayList<>();
    private final List<String> orderCycle = new ArrayList<>();

    public static InferenceResult conflict(Conflict conflict, List<Constraint> constraintList, List<String> cycle) {
        InferenceResult result = new InferenceResult();
        result.feasible = false;
        result.conflict = conflict;
        constraintList.stream().sorted((a, b) -> a.sourceId().compareTo(b.sourceId())).forEach(item -> result.constraints.add(item.toJson()));
        result.orderCycle.addAll(cycle);
        return result;
    }

    public static InferenceResult feasible(List<Constraint> constraintList) {
        InferenceResult result = new InferenceResult();
        result.feasible = true;
        constraintList.stream().sorted((a, b) -> {
            int bySource = a.sourceId().compareTo(b.sourceId());
            if (bySource != 0) return bySource;
            int byFrom = a.from().compareTo(b.from());
            if (byFrom != 0) return byFrom;
            return a.to().compareTo(b.to());
        }).forEach(item -> result.constraints.add(item.toJson()));
        return result;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("feasible", feasible);
        map.put("ruleVersion", gsb.model.AppState.RULE_VERSION);
        if (conflict != null) map.put("conflict", conflict.toJson());
        map.put("intervals", intervals);
        map.put("edges", edges);
        map.put("contemporaneousGroups", contemporaneousGroups);
        map.put("topologicalOrder", topologicalOrder);
        map.put("constraints", constraints);
        if (!orderCycle.isEmpty()) map.put("orderCycle", orderCycle);
        return map;
    }

    public boolean feasible() {
        return feasible;
    }

    public Conflict conflict() {
        return conflict;
    }

    List<Map<String, Object>> intervalsMap() {
        return intervals;
    }

    List<Map<String, Object>> edgesMap() {
        return edges;
    }

    List<List<String>> groups() {
        return contemporaneousGroups;
    }

    void setTopologicalOrder(List<String> order) {
        this.topologicalOrder = order;
    }

    Map<String, Object> freshMap() {
        return new LinkedHashMap<>();
    }
}
