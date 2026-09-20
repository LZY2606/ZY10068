package gsb.model;

import java.util.List;
import java.util.Map;

public record Conflict(String code, String message, List<String> evidenceIds, List<String> datingIds,
                       List<String> constraints, Map<String, Object> details) {
    public Conflict {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        datingIds = datingIds == null ? List.of() : List.copyOf(datingIds);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("code", code);
        map.put("message", message);
        map.put("evidenceIds", evidenceIds);
        map.put("datingIds", datingIds);
        map.put("constraints", constraints);
        if (details != null) map.put("details", details);
        return map;
    }
}
