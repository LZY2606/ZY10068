package gsb.model;

import java.util.Map;

public record Snapshot(String id, String branchId, String label, long createdAt, String ruleVersion,
                       String inputFingerprint, Map<String, Object> projection) {
    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("id", id);
        map.put("branchId", branchId);
        map.put("label", label);
        map.put("createdAt", createdAt);
        map.put("ruleVersion", ruleVersion);
        map.put("inputFingerprint", inputFingerprint);
        map.put("projection", projection);
        return map;
    }
}
