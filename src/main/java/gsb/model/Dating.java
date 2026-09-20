package gsb.model;

import java.util.Map;

public record Dating(String id, String context, Interval interval, String sourceReference, String page,
                     String note, long createdAt, boolean retracted, Long retractedAt, String retractionReason) {
    public Dating {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Dating id is required");
        if (context == null || context.isBlank()) throw new IllegalArgumentException("Dating context is required");
    }

    public static Dating parse(Map<String, Object> map, String id, long createdAt) {
        return new Dating(id, Json.string(map, "context").trim(), Interval.parse(Json.object(Json.required(map, "interval"))),
                Json.optionalString(map, "sourceReference", ""), Json.optionalString(map, "page", ""),
                Json.optionalString(map, "note", ""), createdAt, false, null, null);
    }

    public Dating retract(long at, String reason) {
        return new Dating(id, context, interval, sourceReference, page, note, createdAt, true, at, reason);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("id", id);
        map.put("context", context);
        map.put("interval", interval.toJson());
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
