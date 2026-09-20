package gsb.infer;

import java.util.Map;

public record Constraint(String from, String to, long weight, String sourceId, String kind, String description) {
    public Map<String, Object> toJson() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("from", from);
        map.put("to", to);
        map.put("weight", weight);
        map.put("sourceId", sourceId);
        map.put("kind", kind);
        map.put("description", description);
        return map;
    }
}
