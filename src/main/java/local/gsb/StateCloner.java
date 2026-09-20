package local.gsb;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class StateCloner {
    private StateCloner() {
    }

    static State clone(State source) {
        State copy = new State();
        copy.seq = source.seq;
        source.contexts.forEach((id, value) -> copy.contexts.put(id, ContextRecord.fromJson(value.toJson())));
        source.datingEvidence.forEach((id, value) -> copy.datingEvidence.put(id, DatingEvidence.fromJson(value.toJson())));
        source.relationEvidence.forEach((id, value) -> copy.relationEvidence.put(id, RelationEvidence.fromJson(value.toJson())));
        source.branches.forEach((id, value) -> copy.branches.put(id, Branch.fromJson(value.toJson())));
        source.snapshots.forEach((id, value) -> copy.snapshots.put(id, Snapshot.fromJson(value.toJson())));
        source.operations.forEach((id, value) -> copy.operations.put(id, new OperationRecord(
                value.idempotencyKey(),
                value.operationId(),
                value.batchSeq(),
                value.batchId(),
                value.createdAt(),
                Json.object(Json.parse(Json.write(value.response()))))));
        source.jobs.forEach((id, value) -> copy.jobs.put(id, JobRecord.fromJson(value.toJson())));
        cloneSets(source.branchContexts, copy.branchContexts);
        cloneSets(source.branchDating, copy.branchDating);
        cloneSets(source.branchRelations, copy.branchRelations);
        cloneSets(source.branchDatingRetractions, copy.branchDatingRetractions);
        cloneSets(source.branchRelationRetractions, copy.branchRelationRetractions);
        return copy;
    }

    private static void cloneSets(Map<String, Set<String>> source, Map<String, Set<String>> target) {
        source.forEach((key, value) -> target.put(key, new java.util.LinkedHashSet<>(value)));
    }
}
