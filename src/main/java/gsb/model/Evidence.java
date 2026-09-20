package gsb.model;

import java.util.Map;

public record Evidence(String id, String source, String target, RelationType relation, int strength,
                       String sourceReference, String page, String note, long createdAt, boolean retracted,
                       Long retractedAt, String retractionReason) {
    public Evidence {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Evidence id is required");
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            throw new IllegalArgumentException("Evidence endpoints are required");
        }
        if (source.equals(target)) throw new IllegalArgumentException("Evidence cannot connect a context to itself");
        if (strength < 1 || strength > 5) throw new IllegalArgumentException("Evidence strength must be 1..5");
    }

    public static Evidence parse(Map<String, Object> map, String id, long createdAt) {
        return new Evidence(id,
                Json.string(map, "source").trim(),
                Json.string(map, "target").trim(),
                RelationType.parse(map.get("relation")),
                (int) asLong(Json.required(map, "strength")),
                Json.optionalString(map, "sourceReference", ""),
                Json.optionalString(map, "page", ""),
                Json.optionalString(map, "note", ""),
                createdAt, false, null, null);
    }

    public Evidence retract(long at, String reason) {
        return new Evidence(id, source, target, relation, strength, sourceReference, page, note, createdAt,
                true, at, reason);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("id", id);
        map.put("source", source);
        map.put("target", target);
        map.put("relation", relation.wire());
        map.put("strength", strength);
        map.put("sourceReference", sourceReference);
        map.put("page", page);
        map.put("note", note);
        map.put("createdAt", createdAt);
        map.put("retracted", retracted);
        map.put("retractedAt", retractedAt);
        map.put("retractionReason", retractionReason);
        return map;
    }
}
