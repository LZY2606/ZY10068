package gsb.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BranchState {
    public final Map<String, ContextInfo> contexts = new LinkedHashMap<>();
    public final Map<String, Evidence> evidences = new LinkedHashMap<>();
    public final Map<String, Dating> datings = new LinkedHashMap<>();
    public final List<Map<String, Object>> rules = new ArrayList<>();
    public final List<String> eventIds = new ArrayList<>();

    public BranchState copy() {
        BranchState copy = new BranchState();
        copy.contexts.putAll(contexts);
        copy.evidences.putAll(evidences);
        copy.datings.putAll(datings);
        copy.rules.addAll(rules);
        copy.eventIds.addAll(eventIds);
        return copy;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        List<Object> contextList = Json.array();
        contexts.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(item -> contextList.add(item.toJson()));
        List<Object> evidenceList = Json.array();
        evidences.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(item -> evidenceList.add(item.toJson()));
        List<Object> datingList = Json.array();
        datings.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(item -> datingList.add(item.toJson()));
        map.put("contexts", contextList);
        map.put("evidences", evidenceList);
        map.put("datings", datingList);
        map.put("rules", rules);
        map.put("eventIds", eventIds);
        return map;
    }
}
