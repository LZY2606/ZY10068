package gsb.model;

import java.util.Map;

public record ContextInfo(String id, String label, String description, long createdAt) {
    public static ContextInfo parse(Map<String, Object> map, long createdAt) {
        String id = Json.string(map, "id").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Context id cannot be empty");
        return new ContextInfo(id,
                Json.optionalString(map, "label", id),
                Json.optionalString(map, "description", ""),
                createdAt);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("id", id);
        map.put("label", label);
        map.put("description", description);
        map.put("createdAt", createdAt);
        return map;
    }
}
