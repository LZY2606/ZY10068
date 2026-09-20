package gsb.model;

import java.util.Map;

public record JobRecord(String id, String branchId, String idempotencyKey, String status, long requestedAt,
                        Long completedAt, Map<String, Object> result, String fingerprint) {
    public JobRecord complete(long at, Map<String, Object> completedResult, String resultFingerprint) {
        return new JobRecord(id, branchId, idempotencyKey, "completed", requestedAt, at, completedResult, resultFingerprint);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("id", id);
        map.put("branchId", branchId);
        map.put("idempotencyKey", idempotencyKey);
        map.put("status", status);
        map.put("requestedAt", requestedAt);
        map.put("completedAt", completedAt);
        map.put("fingerprint", fingerprint);
        if (result != null) map.put("result", result);
        return map;
    }
}
