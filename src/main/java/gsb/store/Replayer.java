package gsb.store;

import gsb.model.AppState;
import gsb.model.BranchInfo;
import gsb.model.BranchState;
import gsb.model.ContextInfo;
import gsb.model.Dating;
import gsb.model.Evidence;
import gsb.model.JobRecord;
import gsb.model.Json;
import gsb.model.RelationType;
import gsb.model.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Replayer {
    void applyTransaction(AppState state, Map<String, Object> transaction) {
        String format = Json.optionalString(transaction, "formatVersion", AppState.FORMAT_VERSION);
        if (!AppState.FORMAT_VERSION.equals(format)) {
            throw new IllegalStateException("Unsupported persistence format: " + format);
        }
        Object key = transaction.get("idempotencyKey");
        if (key != null && state.idempotency.containsKey(String.valueOf(key))) {
            return;
        }
        Map<String, Object> response = Json.object(transaction.getOrDefault("response", Json.object()));
        if (key != null) state.idempotency.put(String.valueOf(key), response);
        for (Object eventValue : Json.array(transaction.get("events"))) {
            Map<String, Object> event = Json.object(eventValue);
            applyEvent(state, event);
            state.events.add(event);
        }
        Map<String, Object> snapshotMap = Json.object(transaction.getOrDefault("snapshot", Json.object()));
        if (!snapshotMap.isEmpty()) applySnapshot(state, snapshotMap);
    }

    private void applyEvent(AppState state, Map<String, Object> event) {
        String type = Json.string(event, "type");
        switch (type) {
            case "CONTEXT_ADDED" -> applyContextAdded(state, event);
            case "EVIDENCE_ADDED" -> applyEvidenceAdded(state, event);
            case "DATING_ADDED" -> applyDatingAdded(state, event);
            case "EVIDENCE_RETRACTED", "DATING_RETRACTED" -> applyRetracted(state, event);
            case "EVIDENCE_INTERPRETED" -> applyInterpreted(state, event);
            case "BRANCH_CREATED" -> applyBranchCreated(state, event);
            case "BRANCH_STATUS_CHANGED" -> applyBranchStatus(state, event);
            case "BRANCH_MERGED" -> applyBranchMerged(state, event);
            case "JOB_REQUESTED" -> applyJobRequested(state, event);
            case "JOB_COMPLETED" -> applyJobCompleted(state, event);
            default -> throw new IllegalStateException("Unsupported event type: " + type);
        }
    }

    private void applyContextAdded(AppState state, Map<String, Object> event) {
        String branchId = Json.string(event, "branchId");
        Map<String, Object> payload = Json.object(Json.required(event, "payload"));
        long at = asLong(event, "at");
        ContextInfo info = ContextInfo.parse(payload, asLong(payload, "createdAt", at));
        state.branch(branchId).contexts.put(info.id(), info);
        state.branch(branchId).eventIds.add(Json.string(event, "id"));
    }

    private void applyEvidenceAdded(AppState state, Map<String, Object> event) {
        String branchId = Json.string(event, "branchId");
        Map<String, Object> payload = Json.object(Json.required(event, "payload"));
        long at = asLong(event, "at");
        Evidence evidence = Evidence.parse(payload, Json.string(payload, "id"), asLong(payload, "createdAt", at));
        state.branch(branchId).evidences.put(evidence.id(), evidence);
        state.branch(branchId).eventIds.add(Json.string(event, "id"));
    }

    private void applyDatingAdded(AppState state, Map<String, Object> event) {
        String branchId = Json.string(event, "branchId");
        Map<String, Object> payload = Json.object(Json.required(event, "payload"));
        long at = asLong(event, "at");
        Dating dating = Dating.parse(payload, Json.string(payload, "id"), asLong(payload, "createdAt", at));
        state.branch(branchId).datings.put(dating.id(), dating);
        state.branch(branchId).eventIds.add(Json.string(event, "id"));
    }

    private void applyRetracted(AppState state, Map<String, Object> event) {
        String branchId = Json.string(event, "branchId");
        String id = Json.string(event, "evidenceOrDatingId");
        long at = asLong(event, "at");
        String reason = Json.optionalString(event, "reason", "");
        BranchState branchState = state.branch(branchId);
        Evidence evidence = branchState.evidences.get(id);
        if (evidence != null) {
            branchState.evidences.put(id, evidence.retract(at, reason));
        } else {
            Dating dating = branchState.datings.get(id);
            if (dating == null) throw new IllegalStateException("Cannot retract missing evidence or dating: " + id);
            branchState.datings.put(id, dating.retract(at, reason));
        }
        branchState.eventIds.add(Json.string(event, "id"));
    }

    private void applyInterpreted(AppState state, Map<String, Object> event) {
        String branchId = Json.string(event, "branchId");
        String id = Json.string(event, "evidenceId");
        RelationType relation = RelationType.parse(event.get("relation"));
        BranchState branchState = state.branch(branchId);
        Evidence existing = branchState.evidences.get(id);
        if (existing == null) throw new IllegalStateException("Cannot interpret missing evidence: " + id);
        Evidence updated = new Evidence(existing.id(), existing.source(), existing.target(), relation, existing.strength(),
                existing.sourceReference(), existing.page(), existing.note(), existing.createdAt(),
                existing.retracted(), existing.retractedAt(), existing.retractionReason());
        branchState.evidences.put(id, updated);
        branchState.eventIds.add(Json.string(event, "id"));
    }

    private void applyBranchCreated(AppState state, Map<String, Object> event) {
        String id = Json.string(event, "branchId");
        String parentId = Json.string(event, "parentBranchId");
        long at = asLong(event, "at");
        BranchInfo parentInfo = state.info(parentId);
        String baseSnapshotId = Json.optionalString(event, "baseSnapshotId", parentInfo.confirmedSnapshotId());
        BranchInfo info = new BranchInfo(id, Json.string(event, "name"), "open", parentId, baseSnapshotId,
                Json.optionalString(event, "confirmedSnapshotId", null), at);
        Map<String, Object> projection = Json.object(Json.required(event, "projection"));
        BranchState branchState = ProjectionCodec.fromMap(projection);
        state.branches.put(id, branchState);
        state.branchInfos.put(id, info);
    }

    private void applyBranchStatus(AppState state, Map<String, Object> event) {
        String id = Json.string(event, "branchId");
        state.branchInfos.put(id, state.info(id).merged(Json.string(event, "status")));
    }

    private void applyBranchMerged(AppState state, Map<String, Object> event) {
        BranchState merged = ProjectionCodec.fromMap(Json.object(Json.required(event, "projection")));
        state.branches.put("main", merged);
        String source = Json.string(event, "sourceBranchId");
        state.branchInfos.put("main", state.info("main").merged("open"));
        state.branchInfos.put(source, state.info(source).merged("merged"));
    }

    private void applyJobRequested(AppState state, Map<String, Object> event) {
        String key = Json.string(event, "idempotencyKey");
        JobRecord job = new JobRecord(Json.string(event, "jobId"), Json.string(event, "branchId"), key, "pending",
                asLong(event, "at"), null, null, null);
        state.jobs.put(job.id(), job);
    }

    private void applyJobCompleted(AppState state, Map<String, Object> event) {
        String jobId = Json.string(event, "jobId");
        JobRecord existing = state.jobs.get(jobId);
        if (existing == null) throw new IllegalStateException("Cannot complete missing job: " + jobId);
        state.jobs.put(jobId, existing.complete(asLong(event, "at"), Json.object(Json.required(event, "result")),
                Json.optionalString(event, "fingerprint", null)));
    }

    private void applySnapshot(AppState state, Map<String, Object> map) {
        Snapshot snapshot = new Snapshot(Json.string(map, "id"), Json.string(map, "branchId"),
                Json.optionalString(map, "label", "Published snapshot"), asLong(map, "createdAt"),
                Json.optionalString(map, "ruleVersion", AppState.RULE_VERSION),
                Json.string(map, "inputFingerprint"), Json.object(Json.required(map, "projection")));
        state.snapshots.put(snapshot.id(), snapshot);
        String branchId = snapshot.branchId();
        state.branchInfos.put(branchId, state.info(branchId).confirmed(snapshot.id()));
    }

    private long asLong(Map<String, Object> map, String key) {
        return IntervalCodec.asLong(Json.required(map, key));
    }

    private long asLong(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value == null ? fallback : IntervalCodec.asLong(value);
    }
}
