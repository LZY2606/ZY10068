package local.gsb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ContextRecord(String id, String label, String kind, String note, long createdAt) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("kind", kind == null ? "context" : kind);
        map.put("note", note == null ? "" : note);
        map.put("createdAt", createdAt);
        return map;
    }

    static ContextRecord fromJson(Map<String, Object> map) {
        return new ContextRecord(
                Json.requireStr(map, "id"),
                Json.requireStr(map, "label"),
                Json.str(map, "kind"),
                Json.str(map, "note"),
                Json.integer(map, "createdAt") == null ? 0L : Json.integer(map, "createdAt"));
    }
}

record DatingEvidence(
        String id,
        String evidenceKey,
        String branchId,
        String contextId,
        Long lower,
        boolean lowerOpen,
        Long upper,
        boolean upperOpen,
        String source,
        String pages,
        String note,
        String strength,
        long createdAt
) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("evidenceKey", evidenceKey == null ? id : evidenceKey);
        map.put("branchId", branchId);
        map.put("contextId", contextId);
        map.put("lower", lower);
        map.put("lowerOpen", lowerOpen);
        map.put("upper", upper);
        map.put("upperOpen", upperOpen);
        map.put("source", source == null ? "" : source);
        map.put("pages", pages == null ? "" : pages);
        map.put("note", note == null ? "" : note);
        map.put("strength", Rules.normalizeStrength(strength));
        map.put("createdAt", createdAt);
        return map;
    }

    static DatingEvidence fromJson(Map<String, Object> map) {
        return new DatingEvidence(
                Json.requireStr(map, "id"),
                Json.str(map, "evidenceKey"),
                Json.requireStr(map, "branchId"),
                Json.requireStr(map, "contextId"),
                Json.integer(map, "lower"),
                Boolean.TRUE.equals(Json.bool(map, "lowerOpen")),
                Json.integer(map, "upper"),
                Boolean.TRUE.equals(Json.bool(map, "upperOpen")),
                Json.str(map, "source"),
                Json.str(map, "pages"),
                Json.str(map, "note"),
                Json.str(map, "strength"),
                Json.integer(map, "createdAt") == null ? 0L : Json.integer(map, "createdAt"));
    }
}

record RelationEvidence(
        String id,
        String evidenceKey,
        String branchId,
        String fromContextId,
        String toContextId,
        String relation,
        String source,
        String pages,
        String note,
        String strength,
        long createdAt
) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("evidenceKey", evidenceKey == null ? id : evidenceKey);
        map.put("branchId", branchId);
        map.put("fromContextId", fromContextId);
        map.put("toContextId", toContextId);
        map.put("relation", Rules.normalizeRelation(relation));
        map.put("source", source == null ? "" : source);
        map.put("pages", pages == null ? "" : pages);
        map.put("note", note == null ? "" : note);
        map.put("strength", Rules.normalizeStrength(strength));
        map.put("createdAt", createdAt);
        return map;
    }

    static RelationEvidence fromJson(Map<String, Object> map) {
        return new RelationEvidence(
                Json.requireStr(map, "id"),
                Json.str(map, "evidenceKey"),
                Json.requireStr(map, "branchId"),
                Json.requireStr(map, "fromContextId"),
                Json.requireStr(map, "toContextId"),
                Json.requireStr(map, "relation"),
                Json.str(map, "source"),
                Json.str(map, "pages"),
                Json.str(map, "note"),
                Json.str(map, "strength"),
                Json.integer(map, "createdAt") == null ? 0L : Json.integer(map, "createdAt"));
    }
}

record Branch(
        String id,
        String name,
        String parentBranchId,
        String sourceSnapshotId,
        String status,
        long createdAt,
        String mergedIntoBranchId
) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("parentBranchId", parentBranchId);
        map.put("sourceSnapshotId", sourceSnapshotId == null ? "" : sourceSnapshotId);
        map.put("status", status);
        map.put("createdAt", createdAt);
        map.put("mergedIntoBranchId", mergedIntoBranchId == null ? "" : mergedIntoBranchId);
        return map;
    }

    static Branch fromJson(Map<String, Object> map) {
        return new Branch(
                Json.requireStr(map, "id"),
                Json.requireStr(map, "name"),
                Json.str(map, "parentBranchId"),
                Json.str(map, "sourceSnapshotId"),
                Json.requireStr(map, "status"),
                Json.integer(map, "createdAt") == null ? 0L : Json.integer(map, "createdAt"),
                Json.str(map, "mergedIntoBranchId"));
    }
}

record Snapshot(
        String id,
        String branchId,
        String name,
        long createdAt,
        long eventSeq,
        String hash,
        String note
) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("branchId", branchId);
        map.put("name", name);
        map.put("createdAt", createdAt);
        map.put("eventSeq", eventSeq);
        map.put("hash", hash);
        map.put("note", note == null ? "" : note);
        return map;
    }

    static Snapshot fromJson(Map<String, Object> map) {
        return new Snapshot(
                Json.requireStr(map, "id"),
                Json.requireStr(map, "branchId"),
                Json.requireStr(map, "name"),
                Json.integer(map, "createdAt") == null ? 0L : Json.integer(map, "createdAt"),
                Json.integer(map, "eventSeq") == null ? 0L : Json.integer(map, "eventSeq"),
                Json.requireStr(map, "hash"),
                Json.str(map, "note"));
    }
}

record OperationRecord(
        String idempotencyKey,
        String operationId,
        long batchSeq,
        String batchId,
        long createdAt,
        Map<String, Object> response
) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("idempotencyKey", idempotencyKey);
        map.put("operationId", operationId);
        map.put("batchSeq", batchSeq);
        map.put("batchId", batchId);
        map.put("createdAt", createdAt);
        map.put("response", response);
        return map;
    }

    static OperationRecord fromJson(Map<String, Object> map) {
        return new OperationRecord(
                Json.requireStr(map, "idempotencyKey"),
                Json.requireStr(map, "operationId"),
                Json.integer(map, "batchSeq") == null ? 0L : Json.integer(map, "batchSeq"),
                Json.requireStr(map, "batchId"),
                Json.integer(map, "createdAt") == null ? 0L : Json.integer(map, "createdAt"),
                Json.object(map.get("response")));
    }
}

record JobRecord(
        String id,
        String branchId,
        long batchSeq,
        String state,
        int attempts,
        long updatedAt,
        String error,
        Map<String, Object> result
) {
    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("branchId", branchId);
        map.put("batchSeq", batchSeq);
        map.put("state", state);
        map.put("attempts", attempts);
        map.put("updatedAt", updatedAt);
        map.put("error", error == null ? "" : error);
        map.put("result", result == null ? Map.of() : result);
        return map;
    }

    static JobRecord fromJson(Map<String, Object> map) {
        Map<String, Object> result = map.get("result") == null ? Map.of() : Json.object(map.get("result"));
        return new JobRecord(
                Json.requireStr(map, "id"),
                Json.requireStr(map, "branchId"),
                Json.integer(map, "batchSeq") == null ? 0L : Json.integer(map, "batchSeq"),
                Json.requireStr(map, "state"),
                Json.integer(map, "attempts") == null ? 0 : Json.integer(map, "attempts").intValue(),
                Json.integer(map, "updatedAt") == null ? 0L : Json.integer(map, "updatedAt"),
                Json.str(map, "error"),
                result);
    }
}

final class State {
    long seq;
    final Map<String, ContextRecord> contexts = new LinkedHashMap<>();
    final Map<String, DatingEvidence> datingEvidence = new LinkedHashMap<>();
    final Map<String, RelationEvidence> relationEvidence = new LinkedHashMap<>();
    final Map<String, Branch> branches = new LinkedHashMap<>();
    final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    final Map<String, OperationRecord> operations = new LinkedHashMap<>();
    final Map<String, JobRecord> jobs = new LinkedHashMap<>();
    final Map<String, Set<String>> branchContexts = new LinkedHashMap<>();
    final Map<String, Set<String>> branchDating = new LinkedHashMap<>();
    final Map<String, Set<String>> branchRelations = new LinkedHashMap<>();
    final Map<String, Set<String>> branchDatingRetractions = new LinkedHashMap<>();
    final Map<String, Set<String>> branchRelationRetractions = new LinkedHashMap<>();

    Set<String> contextIds(String branchId) {
        return branchContexts.computeIfAbsent(branchId, ignored -> new LinkedHashSet<>());
    }

    Set<String> datingIds(String branchId) {
        return branchDating.computeIfAbsent(branchId, ignored -> new LinkedHashSet<>());
    }

    Set<String> relationIds(String branchId) {
        return branchRelations.computeIfAbsent(branchId, ignored -> new LinkedHashSet<>());
    }

    Set<String> datingRetractions(String branchId) {
        return branchDatingRetractions.computeIfAbsent(branchId, ignored -> new LinkedHashSet<>());
    }

    Set<String> relationRetractions(String branchId) {
        return branchRelationRetractions.computeIfAbsent(branchId, ignored -> new LinkedHashSet<>());
    }

    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("formatVersion", 1);
        map.put("ruleVersion", Rules.VERSION);
        map.put("seq", seq);
        map.put("contexts", valuesToJson(contexts));
        map.put("datingEvidence", valuesToJson(datingEvidence));
        map.put("relationEvidence", valuesToJson(relationEvidence));
        map.put("branches", valuesToJson(branches));
        map.put("snapshots", valuesToJson(snapshots));
        map.put("operations", valuesToJson(operations));
        map.put("jobs", valuesToJson(jobs));
        map.put("branchContexts", setsToJson(branchContexts));
        map.put("branchDating", setsToJson(branchDating));
        map.put("branchRelations", setsToJson(branchRelations));
        map.put("branchDatingRetractions", setsToJson(branchDatingRetractions));
        map.put("branchRelationRetractions", setsToJson(branchRelationRetractions));
        return map;
    }

    private <T> List<Object> valuesToJson(Map<String, T> map) {
        List<Object> result = new ArrayList<>();
        for (T value : map.values()) {
            if (value instanceof ContextRecord record) result.add(record.toJson());
            else if (value instanceof DatingEvidence record) result.add(record.toJson());
            else if (value instanceof RelationEvidence record) result.add(record.toJson());
            else if (value instanceof Branch record) result.add(record.toJson());
            else if (value instanceof Snapshot record) result.add(record.toJson());
            else if (value instanceof OperationRecord record) result.add(record.toJson());
            else if (value instanceof JobRecord record) result.add(record.toJson());
        }
        return result;
    }

    private Map<String, Object> setsToJson(Map<String, Set<String>> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(key, new ArrayList<>(value)));
        return result;
    }
}

final class Rules {
    static final String VERSION = "gsb-rules-2026-09-21";

    private Rules() {
    }

    static String normalizeRelation(String value) {
        if (value == null) throw new IllegalArgumentException("relation is required");
        return switch (value) {
            case "earlier-than", "contemporaneous" -> value;
            default -> throw new IllegalArgumentException("relation must be earlier-than or contemporaneous");
        };
    }

    static String normalizeStrength(String value) {
        if (value == null || value.isBlank()) return "normal";
        return switch (value) {
            case "weak", "normal", "strong" -> value;
            default -> throw new IllegalArgumentException("strength must be weak, normal or strong");
        };
    }
}
