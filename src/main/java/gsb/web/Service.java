package gsb.web;

import gsb.infer.InferenceEngine;
import gsb.infer.InferenceResult;
import gsb.infer.InferenceView;
import gsb.model.AppState;
import gsb.model.BranchInfo;
import gsb.model.BranchState;
import gsb.model.Conflict;
import gsb.model.ContextInfo;
import gsb.model.Dating;
import gsb.model.Evidence;
import gsb.model.Fingerprints;
import gsb.model.Json;
import gsb.model.JobRecord;
import gsb.model.RelationType;
import gsb.model.Snapshot;
import gsb.store.ProjectionCodec;
import gsb.store.Store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class Service implements AutoCloseable {
    private final Store store;
    private final InferenceEngine engine = new InferenceEngine();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "inference-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> scheduledRecoveries = ConcurrentHashMap.newKeySet();
    private AppState state;

    public Service(Store store) {
        this.store = store;
        this.state = store.load();
        recoverJobs();
    }

    public synchronized Map<String, Object> stateJson() {
        Map<String, Object> map = Json.object();
        map.put("formatVersion", AppState.FORMAT_VERSION);
        map.put("ruleVersion", AppState.RULE_VERSION);
        map.put("currentTime", Instant.now().toString());
        List<Object> branches = Json.array();
        state.branchInfos.keySet().stream().sorted().forEach(id -> branches.add(branchJson(id, false)));
        map.put("branches", branches);
        List<Object> snapshots = Json.array();
        state.snapshots.keySet().stream().sorted().forEach(id -> snapshots.add(state.snapshots.get(id).toJson()));
        map.put("snapshots", snapshots);
        return map;
    }

    public synchronized Map<String, Object> branchJson(String branchId, boolean withInference) {
        BranchInfo info = state.info(branchId);
        BranchState branchState = state.branch(branchId);
        Map<String, Object> map = Json.object();
        map.put("info", info.toJson());
        map.put("state", branchState.toJson());
        if (withInference) {
            InferenceResult result = infer(branchId);
            map.put("inference", result.toJson());
        }
        return map;
    }

    public synchronized InferenceResult infer(String branchId) {
        return engine.analyze(InferenceView.of(state.branch(branchId)));
    }

    public synchronized Map<String, Object> batch(String branchId, Map<String, Object> request) {
        requireOpen(branchId);
        BranchInfo info = state.info(branchId);
        String idempotencyKey = Json.optionalString(request, "idempotencyKey", "");
        if (!idempotencyKey.isBlank()) {
            Map<String, Object> existing = state.idempotency.get(idempotencyKey);
            if (existing != null) return existing;
        }
        List<Map<String, Object>> operations = Json.array(request.get("operations")).stream().map(Json::object).toList();
        if (operations.isEmpty()) throw new ApiException(422, "EMPTY_BATCH", "Batch requires at least one operation");
        BranchState trial = state.branch(branchId).copy();
        List<Map<String, Object>> events = new ArrayList<>();
        long now = System.currentTimeMillis();
        String txnId = nextId("txn", now);
        for (int index = 0; index < operations.size(); index++) {
            Map<String, Object> operation = operations.get(index);
            String eventId = txnId + "-e" + (index + 1);
            applyOperation(branchId, trial, operation, events, eventId, now);
        }
        InferenceResult result = engine.analyze(InferenceView.of(trial));
        if (!result.feasible()) {
            Map<String, Object> error = Json.object();
            error.put("error", result.conflict().toJson());
            error.put("committed", false);
            throw new ApiException(422, result.conflict().code(), result.conflict().message()) {
                @Override public Map<String, Object> body() { return error; }
            };
        }
        Map<String, Object> response = Json.object();
        response.put("committed", true);
        response.put("transactionId", txnId);
        response.put("branchId", branchId);
        response.put("eventIds", events.stream().map(event -> event.get("id")).toList());
        response.put("inference", result.toJson());
        appendTransaction(txnId, idempotencyKey, events, Json.object(), response);
        return response;
    }

    private void applyOperation(String branchId, BranchState trial, Map<String, Object> operation,
                                List<Map<String, Object>> events, String eventId, long now) {
        String op = Json.string(operation, "op");
        switch (op) {
            case "addContext" -> addContext(branchId, trial, operation, events, eventId, now);
            case "addEvidence" -> addEvidence(branchId, trial, operation, events, eventId, now);
            case "addDating" -> addDating(branchId, trial, operation, events, eventId, now);
            case "retract" -> retract(branchId, trial, operation, events, eventId, now);
            case "interpretEvidence" -> interpretEvidence(branchId, trial, operation, events, eventId, now);
            default -> throw new ApiException(422, "UNSUPPORTED_OPERATION", "Unsupported batch op: " + op);
        }
    }

    private void addContext(String branchId, BranchState trial, Map<String, Object> operation,
                            List<Map<String, Object>> events, String eventId, long now) {
        Map<String, Object> payload = Json.object(Json.required(operation, "context"));
        ContextInfo info = ContextInfo.parse(payload, now);
        if (trial.contexts.containsKey(info.id())) throw new ApiException(409, "CONTEXT_EXISTS", "Context already exists: " + info.id());
        trial.contexts.put(info.id(), info);
        events.add(event("CONTEXT_ADDED", eventId, branchId, now, map -> map.put("payload", info.toJson())));
    }

    private void addEvidence(String branchId, BranchState trial, Map<String, Object> operation,
                             List<Map<String, Object>> events, String eventId, long now) {
        Map<String, Object> payload = Json.object(Json.required(operation, "evidence"));
        String id = Json.optionalString(payload, "id", "ev" + (trial.evidences.size() + 1) + "-" + Fingerprints.sha256(Json.canonical(payload)).substring(0, 8));
        if (trial.evidences.containsKey(id)) throw new ApiException(409, "EVIDENCE_EXISTS", "Evidence already exists: " + id);
        requireContext(trial, Json.string(payload, "source"));
        requireContext(trial, Json.string(payload, "target"));
        Evidence evidence = Evidence.parse(payload, id, now);
        trial.evidences.put(id, evidence);
        Map<String, Object> stored = evidence.toJson();
        events.add(event("EVIDENCE_ADDED", eventId, branchId, now, map -> map.put("payload", stored)));
    }

    private void addDating(String branchId, BranchState trial, Map<String, Object> operation,
                           List<Map<String, Object>> events, String eventId, long now) {
        Map<String, Object> payload = Json.object(Json.required(operation, "dating"));
        String id = Json.optionalString(payload, "id", "dt" + (trial.datings.size() + 1) + "-" + Fingerprints.sha256(Json.canonical(payload)).substring(0, 8));
        if (trial.datings.containsKey(id)) throw new ApiException(409, "DATING_EXISTS", "Dating already exists: " + id);
        requireContext(trial, Json.string(payload, "context"));
        Dating dating = Dating.parse(payload, id, now);
        trial.datings.put(id, dating);
        events.add(event("DATING_ADDED", eventId, branchId, now, map -> map.put("payload", dating.toJson())));
    }

    private void retract(String branchId, BranchState trial, Map<String, Object> operation,
                         List<Map<String, Object>> events, String eventId, long now) {
        String id = Json.string(operation, "id");
        Evidence evidence = trial.evidences.get(id);
        Dating dating = trial.datings.get(id);
        if (evidence == null && dating == null) throw new ApiException(404, "SOURCE_MISSING", "No evidence or dating with id " + id);
        if (evidence != null && evidence.retracted()) throw new ApiException(409, "ALREADY_RETRACTED", "Evidence already retracted: " + id);
        if (dating != null && dating.retracted()) throw new ApiException(409, "ALREADY_RETRACTED", "Dating already retracted: " + id);
        if (evidence != null) trial.evidences.put(id, evidence.retract(now, Json.optionalString(operation, "reason", "")));
        if (dating != null) trial.datings.put(id, dating.retract(now, Json.optionalString(operation, "reason", "")));
        Map<String, Object> payload = Json.object();
        payload.put("evidenceOrDatingId", id);
        payload.put("reason", Json.optionalString(operation, "reason", ""));
        events.add(event(evidence != null ? "EVIDENCE_RETRACTED" : "DATING_RETRACTED", eventId, branchId, now, map -> map.putAll(payload)));
    }

    private void interpretEvidence(String branchId, BranchState trial, Map<String, Object> operation,
                                   List<Map<String, Object>> events, String eventId, long now) {
        String id = Json.string(operation, "id");
        Evidence evidence = trial.evidences.get(id);
        if (evidence == null) throw new ApiException(404, "EVIDENCE_MISSING", "No evidence with id " + id);
        RelationType relation = RelationType.parse(operation.get("relation"));
        Evidence updated = new Evidence(evidence.id(), evidence.source(), evidence.target(), relation, evidence.strength(),
                evidence.sourceReference(), evidence.page(), evidence.note(), evidence.createdAt(), evidence.retracted(), evidence.retractedAt(), evidence.retractionReason());
        trial.evidences.put(id, updated);
        events.add(event("EVIDENCE_INTERPRETED", eventId, branchId, now, map -> {
            map.put("evidenceId", id);
            map.put("relation", relation.wire());
        }));
    }

    public synchronized Map<String, Object> createBranch(Map<String, Object> request) {
        String parentId = Json.optionalString(request, "parentBranchId", "main");
        BranchInfo parent = state.info(parentId);
        if (!"open".equals(parent.status())) throw new ApiException(409, "BRANCH_NOT_OPEN", "Parent branch is not open");
        long now = System.currentTimeMillis();
        String id = Json.optionalString(request, "id", "branch-" + (state.branchInfos.size() + 1) + "-" + Long.toString(now, 36));
        if (state.branchInfos.containsKey(id)) throw new ApiException(409, "BRANCH_EXISTS", "Branch exists: " + id);
        BranchState copy = state.branch(parentId).copy();
        Map<String, Object> event = event("BRANCH_CREATED", nextId("branch-event", now), parentId, now, map -> {
            map.put("branchId", id);
            map.put("name", Json.optionalString(request, "name", id));
            map.put("parentBranchId", parentId);
            map.put("baseSnapshotId", parent.confirmedSnapshotId());
            map.put("confirmedSnapshotId", parent.confirmedSnapshotId());
            map.put("projection", copy.toJson());
        });
        Map<String, Object> response = Json.object();
        response.put("created", true);
        response.put("branchId", id);
        appendTransaction(nextId("branch", now), Json.optionalString(request, "idempotencyKey", ""), List.of(event), Json.object(), response);
        return branchJson(id, true);
    }

    public synchronized Map<String, Object> publish(String branchId, Map<String, Object> request) {
        BranchState branchState = state.branch(branchId);
        InferenceResult inference = engine.analyze(InferenceView.of(branchState));
        if (!inference.feasible()) throw conflictException(inference.conflict());
        long now = System.currentTimeMillis();
        String snapshotId = Json.optionalString(request, "id", "snap-" + Long.toString(now, 36) + "-" + Fingerprints.sha256(branchState.toJson()).substring(0, 8));
        if (state.snapshots.containsKey(snapshotId)) throw new ApiException(409, "SNAPSHOT_EXISTS", "Snapshot exists: " + snapshotId);
        String fingerprint = Fingerprints.sha256(branchState.toJson());
        Map<String, Object> snapshot = Json.object();
        snapshot.put("id", snapshotId);
        snapshot.put("branchId", branchId);
        snapshot.put("label", Json.optionalString(request, "label", "Published snapshot"));
        snapshot.put("createdAt", now);
        snapshot.put("ruleVersion", AppState.RULE_VERSION);
        snapshot.put("inputFingerprint", fingerprint);
        snapshot.put("projection", branchState.toJson());
        Map<String, Object> response = Json.object();
        response.put("published", true);
        response.put("snapshotId", snapshotId);
        response.put("frozen", true);
        response.put("snapshot", snapshot);
        appendTransaction(nextId("publish", now), Json.optionalString(request, "idempotencyKey", ""), List.of(), snapshot, response);
        return response;
    }

    public synchronized Map<String, Object> merge(String sourceId, Map<String, Object> request) {
        if ("main".equals(sourceId)) throw new ApiException(422, "MERGE_MAIN", "Main cannot merge into itself");
        BranchInfo sourceInfo = state.info(sourceId);
        BranchInfo mainInfo = state.info("main");
        if (!"open".equals(sourceInfo.status())) throw new ApiException(409, "BRANCH_NOT_OPEN", "Source branch is not open");
        String baseSnapshotId = sourceInfo.baseSnapshotId();
        Map<String, Object> resolutions = Json.object(request.getOrDefault("resolutions", Json.object()));
        Map<String, Object> chosen = Json.object(resolutions.getOrDefault("evidences", Json.object()));
        Map<String, Object> reasons = Json.object(resolutions.getOrDefault("reasons", Json.object()));
        BranchState base = baseSnapshotId == null ? new BranchState() : ProjectionCodec.fromMap(Json.object(state.snapshots.get(baseSnapshotId).projection()));
        BranchState source = state.branch(sourceId).copy();
        BranchState currentMain = state.branch("main").copy();
        MergeResult merged = mergeState(base, source, currentMain, chosen, reasons);
        if (!merged.conflicts.isEmpty()) {
            Map<String, Object> body = Json.object();
            body.put("conflicts", merged.conflicts);
            body.put("requires", "resolutions.evidences keyed by evidence id with value source or main");
            throw new ApiException(409, "MERGE_CONFLICT", "Manual interpretation selection required") {
                @Override public Map<String, Object> body() { return body; }
            };
        }
        InferenceResult inference = engine.analyze(InferenceView.of(merged.state));
        if (!inference.feasible()) throw conflictException(inference.conflict());
        long now = System.currentTimeMillis();
        Map<String, Object> event = event("BRANCH_MERGED", nextId("merge-event", now), "main", now, map -> {
            map.put("sourceBranchId", sourceId);
            map.put("baseSnapshotId", baseSnapshotId);
            map.put("resolutions", resolutions);
            map.put("projection", merged.state.toJson());
        });
        Map<String, Object> response = Json.object();
        response.put("merged", true);
        response.put("sourceBranchId", sourceId);
        response.put("inference", inference.toJson());
        appendTransaction(nextId("merge", now), Json.optionalString(request, "idempotencyKey", ""), List.of(event), Json.object(), response);
        return response;
    }

    private MergeResult mergeState(BranchState base, BranchState source, BranchState main,
                                   Map<String, Object> chosen, Map<String, Object> reasons) {
        MergeResult result = new MergeResult();
        result.state.contexts.clear();
        result.state.evidences.clear();
        result.state.datings.clear();
        result.state.rules.addAll(main.rules);
        Set<String> contextIds = union(base.contexts.keySet(), source.contexts.keySet(), main.contexts.keySet());
        contextIds.stream().sorted().forEach(id -> {
            ContextInfo sourceContext = source.contexts.get(id);
            ContextInfo mainContext = main.contexts.get(id);
            if (sourceContext != null && mainContext != null) result.state.contexts.put(id, mainContext);
            else if (sourceContext != null) result.state.contexts.put(id, sourceContext);
            else if (mainContext != null) result.state.contexts.put(id, mainContext);
        });
        Set<String> evidenceIds = union(base.evidences.keySet(), source.evidences.keySet(), main.evidences.keySet());
        for (String id : evidenceIds.stream().sorted().toList()) {
            Evidence baseEvidence = base.evidences.get(id);
            Evidence sourceEvidence = source.evidences.get(id);
            Evidence mainEvidence = main.evidences.get(id);
            if (!changed(baseEvidence, sourceEvidence) || sourceEvidence == null) {
                if (mainEvidence != null) result.state.evidences.put(id, mainEvidence);
            } else if (!changed(baseEvidence, mainEvidence) || mainEvidence == null) {
                result.state.evidences.put(id, sourceEvidence);
            } else {
                Object choiceValue = chosen.get(id);
                String choice = choiceValue == null ? "" : String.valueOf(choiceValue);
                if (!"source".equals(choice) && !"main".equals(choice)) {
                    result.conflicts.add(conflict(id, "Evidence interpretation differs between source branch and main", baseEvidence, sourceEvidence, mainEvidence));
                    continue;
                }
                if (reasons.get(id) == null || Json.optionalString(reasons, id, "").isBlank()) {
                    throw new ApiException(422, "RESOLUTION_REASON_REQUIRED", "A recorded reason is required for evidence " + id);
                }
                result.state.evidences.put(id, "source".equals(choice) ? sourceEvidence : mainEvidence);
            }
        }
        Set<String> datingIds = union(base.datings.keySet(), source.datings.keySet(), main.datings.keySet());
        for (String id : datingIds.stream().sorted().toList()) {
            Dating sourceDating = source.datings.get(id);
            Dating mainDating = main.datings.get(id);
            if (sourceDating != null && mainDating != null) result.state.datings.put(id, mainDating);
            else if (sourceDating != null) result.state.datings.put(id, sourceDating);
            else if (mainDating != null) result.state.datings.put(id, mainDating);
        }
        return result;
    }

    private Map<String, Object> conflict(String id, String message, Evidence base, Evidence source, Evidence main) {
        Map<String, Object> conflict = Json.object();
        conflict.put("evidenceId", id);
        conflict.put("message", message);
        conflict.put("source", source == null ? null : source.toJson());
        conflict.put("main", main == null ? null : main.toJson());
        conflict.put("base", base == null ? null : base.toJson());
        return conflict;
    }

    private boolean changed(Evidence before, Evidence after) {
        if (before == null) return after != null;
        if (after == null) return true;
        return before.relation() != after.relation() || before.retracted() != after.retracted()
                || before.strength() != after.strength() || !Objects.equals(before.note(), after.note());
    }

    private Set<String> union(Set<String> a, Set<String> b, Set<String> c) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        all.addAll(c);
        return all;
    }

    public Map<String, Object> startJob(String branchId, Map<String, Object> request) {
        Map<String, Object> response;
        Map<String, Object> transaction;
        String jobId;
        synchronized (this) {
            requireOpen(branchId);
            String key = Json.string(request, "idempotencyKey");
            Map<String, Object> existing = state.idempotency.get(key);
            if (existing != null) return existing;
            long now = System.currentTimeMillis();
            jobId = "job-" + Long.toString(now, 36) + "-" + Fingerprints.sha256(branchId + "|" + key).substring(0, 10);
            Map<String, Object> event = event("JOB_REQUESTED", nextId("job-req", now), branchId, now, map -> {
                map.put("jobId", jobId);
                map.put("idempotencyKey", key);
            });
            response = Json.object();
            response.put("jobId", jobId);
            response.put("status", "pending");
            transaction = transaction(nextId("job", now), key, List.of(event), Json.object(), response);
        }
        store.append(transaction);
        synchronized (this) {
            state = store.load();
        }
        scheduleCompletion(jobId);
        return response;
    }

    public synchronized Map<String, Object> job(String jobId) {
        JobRecord job = state.jobs.get(jobId);
        if (job == null) throw new ApiException(404, "JOB_MISSING", "Unknown job: " + jobId);
        return job.toJson();
    }

    private void scheduleCompletion(String jobId) {
        scheduledRecoveries.add(jobId);
        executor.submit(() -> {
            try {
                completeJob(jobId);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                throw throwable;
            }
        });
    }

    private void recoverJobs() {
        state.jobs.values().stream().filter(job -> "pending".equals(job.status())).forEach(job -> scheduleCompletion(job.id()));
    }

    private void completeJob(String jobId) {
        synchronized (this) {
            JobRecord job = state.jobs.get(jobId);
            if (job == null || "completed".equals(job.status())) return;
            InferenceResult result = engine.analyze(InferenceView.of(state.branch(job.branchId())));
            String fingerprint = Fingerprints.sha256(result.toJson());
            long now = System.currentTimeMillis();
            Map<String, Object> event = event("JOB_COMPLETED", nextId("job-complete", now), job.branchId(), now, map -> {
                map.put("jobId", jobId);
                map.put("result", result.toJson());
                map.put("fingerprint", fingerprint);
            });
            Map<String, Object> response = Json.object();
            response.put("jobId", jobId);
            response.put("status", "completed");
            response.put("fingerprint", fingerprint);
            response.put("result", result.toJson());
            Map<String, Object> transaction = transaction(nextId("job-result", now), job.idempotencyKey() + ":completed", List.of(event), Json.object(), response);
            store.append(transaction);
            state = store.load();
        }
    }

    public synchronized Map<String, Object> compare(List<String> branchIds) {
        List<String> ids = branchIds == null || branchIds.isEmpty() ? state.branchInfos.keySet().stream().sorted().toList() : branchIds.stream().distinct().sorted().toList();
        Map<String, InferenceResult> results = new LinkedHashMap<>();
        for (String id : ids) results.put(id, infer(id));
        Map<String, Object> map = Json.object();
        map.put("branches", ids);
        map.put("universalEdges", universalEdges(results));
        map.put("universalIntervals", universalIntervals(results));
        map.put("branchSpecific", branchSpecific(results));
        map.put("allConclusions", map.get("universalEdges"));
        return map;
    }

    private List<Object> universalEdges(Map<String, InferenceResult> results) {
        Optional<Map<String, Object>> first = results.values().stream().findFirst().map(item -> Json.object(item.toJson()));
        if (first.isEmpty()) return Json.array();
        List<Map<String, Object>> edges = Json.array(first.get().get("edges")).stream().map(Json::object).toList();
        return edges.stream().filter(edge -> results.values().stream().allMatch(result -> hasEdge(Json.object(result.toJson()), edge))).map(item -> (Object) item).toList();
    }

    private boolean hasEdge(Map<String, Object> inference, Map<String, Object> expected) {
        return Json.array(inference.get("edges")).stream().map(Json::object).anyMatch(edge ->
                Objects.equals(edge.get("from"), expected.get("from")) && Objects.equals(edge.get("to"), expected.get("to")));
    }

    private List<Object> universalIntervals(Map<String, InferenceResult> results) {
        Optional<InferenceResult> first = results.values().stream().findFirst();
        if (first.isEmpty()) return Json.array();
        List<Map<String, Object>> intervals = Json.array(first.get().toJson().get("intervals")).stream().map(Json::object).toList();
        return intervals.stream().filter(interval -> results.values().stream().allMatch(result -> sameInterval(Json.object(result.toJson()), interval))).map(item -> (Object) item).toList();
    }

    private boolean sameInterval(Map<String, Object> inference, Map<String, Object> expected) {
        return Json.array(inference.get("intervals")).stream().map(Json::object).anyMatch(interval ->
                Objects.equals(interval.get("contextId"), expected.get("contextId"))
                        && Objects.equals(interval.get("lower"), expected.get("lower"))
                        && Objects.equals(interval.get("upper"), expected.get("upper")));
    }

    private Map<String, Object> branchSpecific(Map<String, InferenceResult> results) {
        Map<String, Object> map = Json.object();
        results.forEach((id, result) -> map.put(id, result.toJson()));
        return map;
    }

    public synchronized Map<String, Object> audit(String branchId) {
        Map<String, Object> map = Json.object();
        map.put("branchId", branchId);
        map.put("ruleVersion", AppState.RULE_VERSION);
        map.put("transactions", store.readTransactions());
        map.put("currentState", state.branch(branchId).toJson());
        BranchInfo info = state.info(branchId);
        if (info.confirmedSnapshotId() != null) {
            Snapshot snapshot = state.snapshots.get(info.confirmedSnapshotId());
            map.put("confirmedSnapshot", snapshot.toJson());
        }
        return map;
    }

    public synchronized Map<String, Object> export(String branchId) {
        BranchState branchState = state.branch(branchId);
        InferenceResult inference = engine.analyze(InferenceView.of(branchState));
        Map<String, Object> map = Json.object();
        map.put("formatVersion", AppState.FORMAT_VERSION);
        map.put("ruleVersion", AppState.RULE_VERSION);
        map.put("branchId", branchId);
        map.put("input", semanticInput(branchState));
        map.put("inference", inference.toJson());
        map.put("fingerprint", Fingerprints.sha256(map));
        return map;
    }

    private void appendTransaction(String txnId, String idempotencyKey, List<Map<String, Object>> events,
                                   Map<String, Object> snapshot, Map<String, Object> response) {
        Map<String, Object> txn = transaction(txnId, idempotencyKey, events, snapshot, response);
        store.append(txn);
        state = store.load();
    }

    private Map<String, Object> semanticInput(BranchState branchState) {
        Map<String, Object> map = Json.object();
        map.put("contexts", branchState.contexts.values().stream().sorted(java.util.Comparator.comparing(ContextInfo::id))
                .map(item -> Map.of("id", item.id(), "label", item.label(), "description", item.description())).toList());
        map.put("evidences", branchState.evidences.values().stream().sorted(java.util.Comparator.comparing(Evidence::id))
                .map(this::semanticEvidence).toList());
        map.put("datings", branchState.datings.values().stream().sorted(java.util.Comparator.comparing(Dating::id))
                .map(this::semanticDating).toList());
        return map;
    }

    private Map<String, Object> semanticEvidence(Evidence item) {
        Map<String, Object> map = Json.object();
        map.put("id", item.id());
        map.put("source", item.source());
        map.put("target", item.target());
        map.put("relation", item.relation().wire());
        map.put("strength", item.strength());
        map.put("sourceReference", item.sourceReference());
        map.put("page", item.page());
        map.put("note", item.note());
        map.put("retracted", item.retracted());
        map.put("retractionReason", item.retractionReason() == null ? "" : item.retractionReason());
        return map;
    }

    private Map<String, Object> semanticDating(Dating item) {
        Map<String, Object> map = Json.object();
        map.put("id", item.id());
        map.put("context", item.context());
        map.put("interval", item.interval().toJson());
        map.put("sourceReference", item.sourceReference());
        map.put("page", item.page());
        map.put("note", item.note());
        map.put("retracted", item.retracted());
        map.put("retractionReason", item.retractionReason() == null ? "" : item.retractionReason());
        return map;
    }

    private Map<String, Object> transaction(String txnId, String idempotencyKey, List<Map<String, Object>> events,
                                            Map<String, Object> snapshot, Map<String, Object> response) {
        Map<String, Object> map = Json.object();
        map.put("formatVersion", AppState.FORMAT_VERSION);
        map.put("ruleVersion", AppState.RULE_VERSION);
        map.put("transactionId", txnId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) map.put("idempotencyKey", idempotencyKey);
        map.put("events", events);
        map.put("snapshot", snapshot);
        map.put("response", response);
        return map;
    }

    private Map<String, Object> event(String type, String id, String branchId, long at, EventCustomizer customizer) {
        Map<String, Object> map = Json.object();
        map.put("type", type);
        map.put("id", id);
        map.put("branchId", branchId);
        map.put("at", at);
        customizer.customize(map);
        return map;
    }

    private String nextId(String prefix, long now) {
        return prefix + "-" + Long.toString(now, 36) + "-" + Long.toString(counter.incrementAndGet(), 36);
    }

    private void requireOpen(String branchId) {
        BranchInfo info = state.info(branchId);
        if ("merged".equals(info.status())) throw new ApiException(409, "BRANCH_MERGED", "Branch has been merged and is read-only: " + branchId);
    }

    private void requireContext(BranchState trial, String id) {
        if (!trial.contexts.containsKey(id)) throw new ApiException(422, "CONTEXT_MISSING", "Unknown context: " + id);
    }

    private ApiException conflictException(Conflict conflict) {
        return new ApiException(422, conflict.code(), conflict.message()) {
            @Override public Map<String, Object> body() {
                return Map.of("error", conflict.toJson(), "committed", false);
            }
        };
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    @FunctionalInterface
    private interface EventCustomizer {
        void customize(Map<String, Object> map);
    }

    private static final class MergeResult {
        private final BranchState state = new BranchState();
        private final List<Map<String, Object>> conflicts = new ArrayList<>();
    }

    private final AtomicLong counter = new AtomicLong();
}
