package gsb.model;

import java.util.Map;

public record BranchInfo(String id, String name, String status, String parentBranchId,
                         String baseSnapshotId, String confirmedSnapshotId, long createdAt) {
    public BranchInfo confirmed(String snapshotId) {
        return new BranchInfo(id, name, status, parentBranchId, baseSnapshotId, snapshotId, createdAt);
    }

    public BranchInfo merged(String status) {
        return new BranchInfo(id, name, status, parentBranchId, baseSnapshotId, confirmedSnapshotId, createdAt);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("id", id);
        map.put("name", name);
        map.put("status", status);
        map.put("parentBranchId", parentBranchId);
        map.put("baseSnapshotId", baseSnapshotId);
        map.put("confirmedSnapshotId", confirmedSnapshotId);
        map.put("createdAt", createdAt);
        return map;
    }
}
