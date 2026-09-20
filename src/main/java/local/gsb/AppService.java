package local.gsb;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import java.util.concurrent.ExecutorService;

final class AppService {
    private final EventStore store;
    private Clock clock;
    private State state;
    private boolean jobRunning;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gsb-analysis");
        thread.setDaemon(true);
        return thread;
    });

    AppService(Path dataDirectory, Clock clock) {
        this.store = new EventStore(dataDirectory);
        this.clock = clock;
        this.state = load();
    }

    synchronized Map<String, Object> batch(Map<String, Object> request) {
        String idempotencyKey = Json.requireStr(request, "idempotencyKey");
        OperationRecord existing = state.operations.get(idempotencyKey);
        if (existing != null) {
            return replayResponse(existing, request);
        }
        List<Object> commandList = Json.list(request.get("commands"));
        if (commandList.isEmpty()) throw new IllegalArgumentException("commands must not be empty");
        long now = clock.millis();
        long seq = state.seq + 1;
        String batchId = "batch-" + seq + "-" + shortId();
        String operationId = "op-" + seq + "-" + shortId();
        State candidate = cloneState(state);
        List<Map<String, Object>> events = new ArrayList<>();
        List<Object> commandResults = new ArrayList<>();
        Set<String> affectedBranches = new LinkedHashSet<>();

        for (Object rawCommand : commandList) {
            Map<String, Object> command = Json.object(rawCommand);
            CommandOutcome outcome = applyCommand(candidate, command, now, seq, batchId);
            events.addAll(outcome.events);
            commandResults.add(outcome.result);
            affectedBranches.addAll(outcome.affectedBranches);
            for (Map<String, Object> generatedEvent : outcome.events) {
                String eventType = Json.requireStr(generatedEvent, "type");
                if (!"job-enqueued".equals(eventType) && !"operation-recorded".equals(eventType)) {
                    replayEvent(candidate, generatedEvent);
                }
            }
        }

        for (String branchId : affectedBranches) {
            String jobId = "job-" + seq + "-" + branchId;
            Map<String, Object> jobEvent = event(seq, batchId, "job-enqueued", now, Map.of(
                    "id", jobId,
                    "branchId", branchId,
                    "batchSeq", seq,
                    "state", "queued"));
            events.add(jobEvent);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "accepted");
        response.put("batchSeq", seq);
        response.put("batchId", batchId);
        response.put("operationId", operationId);
        response.put("results", commandResults);
        response.put("affectedBranchIds", new ArrayList<>(affectedBranches));
        response.put("jobs", affectedBranches.stream().map(branchId -> "job-" + seq + "-" + branchId).toList());

        Map<String, Object> operationEvent = event(seq, batchId, "operation-recorded", now, Map.of(
                "idempotencyKey", idempotencyKey,
                "operationId", operationId,
                "batchSeq", seq,
                "batchId", batchId,
                "response", response));
        events.add(operationEvent);

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("formatVersion", 1);
        batch.put("seq", seq);
        batch.put("batchId", batchId);
        batch.put("createdAt", now);
        batch.put("idempotencyKey", idempotencyKey);
        batch.put("events", events);
        store.appendBatch(batch);
        events.stream()
                .filter(event -> !"job-enqueued".equals(Json.requireStr(event, "type")))
                .filter(event -> !"operation-recorded".equals(Json.requireStr(event, "type")))
                .forEach(event -> replayEvent(state, event));
        replayEvent(state, operationEvent);
        scheduleJobs();
        return response;
    }

    private void scheduleJobs() {
        workers.submit(this::processNextJob);
    }

    private synchronized void processNextJob() {
        JobRecord selected = state.jobs.values().stream()
                .filter(job -> "queued".equals(job.state()))
                .min(Comparator.comparingLong(JobRecord::batchSeq).thenComparing(JobRecord::id))
                .orElse(null);
        if (selected == null) return;
        try {
            Map<String, Object> result = analyzeBranch(state, selected.branchId());
            long now = clock.millis();
            long seq = state.seq + 1;
            String batchId = "job-result-" + seq + "-" + shortId();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", selected.id());
            payload.put("state", "completed");
            payload.put("error", "");
            payload.put("result", result);
            Map<String, Object> finished = event(seq, batchId, "job-finished", now, payload);
            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("formatVersion", 1);
            batch.put("seq", seq);
            batch.put("batchId", batchId);
            batch.put("createdAt", now);
            batch.put("idempotencyKey", "system:" + selected.id());
            batch.put("events", List.of(finished));
            store.appendBatch(batch);
            replayEvent(state, finished);
            notifyAll();
            boolean pending = state.jobs.values().stream().anyMatch(job -> "queued".equals(job.state()));
            if (pending) scheduleJobs();
        } catch (RuntimeException ex) {
            JobRecord failed = new JobRecord(selected.id(), selected.branchId(), selected.batchSeq(),
                    "failed", selected.attempts() + 1, clock.millis(), ex.getMessage(), Map.of());
            state.jobs.put(failed.id(), failed);
            notifyAll();
        }
    }

    synchronized void recover() {
        boolean pending = state.jobs.values().stream().anyMatch(job -> "queued".equals(job.state()));
        if (pending) scheduleJobs();
    }

    synchronized void waitForJobs(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            boolean pending = state.jobs.values().stream().anyMatch(job -> "queued".equals(job.state()));
            if (!pending) return;
            wait(50);
        }
    }

    synchronized void stop() {
        workers.shutdownNow();
    }

    synchronized Map<String, Object> stateView() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleVersion", Rules.VERSION);
        result.put("seq", state.seq);
        result.put("branches", state.branches.values().stream().map(Branch::toJson).toList());
        result.put("snapshots", state.snapshots.values().stream().sorted(Comparator.comparing(Snapshot::id))
                .map(Snapshot::toJson).toList());
        result.put("jobs", state.jobs.values().stream().sorted(Comparator.comparing(JobRecord::id))
                .map(JobRecord::toJson).toList());
        return result;
    }

    synchronized Map<String, Object> branchView(String branchId) {
        ensureBranch(state, branchId);
        JobRecord latest = state.jobs.values().stream()
                .filter(job -> job.branchId().equals(branchId))
                .max(Comparator.comparingLong(JobRecord::batchSeq))
                .orElse(null);
        Map<String, Object> result = analyzeBranch(state, branchId);
        result.put("seq", state.seq);
        result.put("branch", state.branches.get(branchId).toJson());
        result.put("latestJob", latest == null ? null : latest.toJson());
        result.put("visibleDatingIds", state.datingIds(branchId).stream()
                .filter(id -> !state.datingRetractions(branchId).contains(id)).sorted().toList());
        result.put("visibleRelationIds", state.relationIds(branchId).stream()
                .filter(id -> !state.relationRetractions(branchId).contains(id)).sorted().toList());
        result.put("retractedDatingIds", state.datingRetractions(branchId).stream().sorted().toList());
        result.put("retractedRelationIds", state.relationRetractions(branchId).stream().sorted().toList());
        result.put("datingEvidenceAudit", state.datingIds(branchId).stream().sorted()
                .map(state.datingEvidence::get).map(DatingEvidence::toJson).toList());
        result.put("relationEvidenceAudit", state.relationIds(branchId).stream().sorted()
                .map(state.relationEvidence::get).map(RelationEvidence::toJson).toList());
        return result;
    }

    synchronized Map<String, Object> snapshotView(String snapshotId) {
        Snapshot snapshot = state.snapshots.get(snapshotId);
        if (snapshot == null) throw new IllegalArgumentException("unknown snapshot: " + snapshotId);
        State historical = stateAt(snapshot.eventSeq());
        Map<String, Object> result = Reasoner.analyze(snapshot.branchId(),
                visibleContexts(historical, snapshot.branchId()),
                visibleDatingList(historical, snapshot.branchId()),
                visibleRelations(historical, snapshot.branchId()));
        result.put("frozen", true);
        result.put("snapshot", snapshot.toJson());
        result.put("eventSeq", snapshot.eventSeq());
        return result;
    }

    synchronized Map<String, Object> mergePreview(Map<String, Object> request) {
        String sourceId = Json.requireStr(request, "sourceBranchId");
        String targetId = Json.requireStr(request, "targetBranchId");
        ensureBranch(state, sourceId);
        ensureBranch(state, targetId);
        return Map.of("conflicts", mergeConflicts(state, sourceId, targetId));
    }

    synchronized Map<String, Object> compareBranches(List<String> branchIds) {
        List<Map<String, Object>> analyses = new ArrayList<>();
        for (String branchId : branchIds) {
            ensureBranch(state, branchId);
            analyses.add(analyzeBranch(state, branchId));
        }
        Map<String, Object> first = analyses.isEmpty() ? Map.of() : analyses.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branchIds", branchIds);
        result.put("ruleVersion", Rules.VERSION);
        result.put("commonRelations", intersectRelations(analyses));
        result.put("commonIntervals", intersectIntervals(analyses));
        result.put("allFeasible", analyses.stream().allMatch(analysis -> "feasible".equals(analysis.get("status"))));
        result.put("analyses", analyses);
        return result;
    }

    synchronized Map<String, Object> export(String branchId) {
        ensureBranch(state, branchId);
        Map<String, Object> analysis = analyzeBranch(state, branchId);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("formatVersion", 1);
        export.put("ruleVersion", Rules.VERSION);
        export.put("branchId", branchId);
        export.put("contexts", analysis.get("contexts"));
        export.put("datingEvidence", analysis.get("datingEvidence"));
        export.put("relationEvidence", analysis.get("relationEvidence"));
        export.put("components", analysis.get("components"));
        export.put("relations", analysis.get("relations"));
        export.put("directEdgeCount", analysis.get("directEdgeCount"));
        export.put("transitiveEdgeCount", analysis.get("transitiveEdgeCount"));
        export.put("stableTopologicalOrder", analysis.get("stableTopologicalOrder"));
        export.put("digest", Canonical.digest(export));
        export.put("exportSeq", state.seq);
        return export;
    }

    synchronized List<Object> eventOrder(Long fromSeq) {
        long start = fromSeq == null ? 1 : fromSeq;
        List<Object> result = new ArrayList<>();
        for (Path file : store.batchFiles()) {
            Map<String, Object> batch = store.readBatch(file);
            long seq = Json.requireInteger(batch, "seq");
            if (seq < start) continue;
            for (Object event : Json.list(batch.get("events"))) {
                Map<String, Object> enriched = new LinkedHashMap<>(Json.object(event));
                enriched.put("batchSeq", seq);
                enriched.put("batchId", batch.get("batchId"));
                result.add(enriched);
            }
        }
        return result;
    }

    private List<Object> intersectRelations(List<Map<String, Object>> analyses) {
        if (analyses.isEmpty()) return List.of();
        Set<String> common = relationKeys(analyses.get(0));
        for (Map<String, Object> analysis : analyses) common.retainAll(relationKeys(analysis));
        List<Object> result = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(common)) {
            Map<String, Object> relation = new LinkedHashMap<>();
            String[] parts = key.split("\u0000", 3);
            relation.put("fromComponentId", parts[0]);
            relation.put("relation", parts[1]);
            relation.put("toComponentId", parts[2]);
            result.add(relation);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<String> relationKeys(Map<String, Object> analysis) {
        Set<String> result = new LinkedHashSet<>();
        for (Object rawRelation : (List<Object>) analysis.getOrDefault("relations", List.of())) {
            Map<String, Object> relation = Json.object(rawRelation);
            for (Object from : Json.list(relation.get("fromContextIds"))) {
                for (Object to : Json.list(relation.get("toContextIds"))) {
                    if (!from.equals(to)) {
                        result.add(String.join("\u0000",
                                String.valueOf(from),
                                String.valueOf(relation.get("relation")),
                                String.valueOf(to)));
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object> intersectIntervals(List<Map<String, Object>> analyses) {
        if (analyses.isEmpty()) return List.of();
        Map<String, Long> lower = new LinkedHashMap<>();
        Map<String, Long> upper = new LinkedHashMap<>();
        for (Map<String, Object> analysis : analyses) {
            for (Object rawComponent : (List<Object>) analysis.getOrDefault("components", List.of())) {
                Map<String, Object> component = Json.object(rawComponent);
                String id = Json.requireStr(component, "id");
                Map<String, Object> interval = Json.object(component.get("allowedInterval"));
                Long componentLower = Json.integer(interval, "lower");
                Long componentUpper = Json.integer(interval, "upper");
                for (Object contextId : Json.list(component.get("contextIds"))) {
                    if (componentLower != null) lower.merge(String.valueOf(contextId), componentLower, Math::max);
                    if (componentUpper != null) upper.merge(String.valueOf(contextId), componentUpper, Math::min);
                }
            }
        }
        List<Object> result = new ArrayList<>();
        for (String id : new java.util.TreeSet<>(java.util.stream.Stream.concat(lower.keySet().stream(), upper.keySet().stream())
                .collect(java.util.stream.Collectors.toSet()))) {
            Long low = lower.get(id);
            Long high = upper.get(id);
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("contextId", id);
            component.put("lower", low);
            component.put("upper", high);
            component.put("nonEmpty", low == null || high == null || low <= high);
            result.add(component);
        }
        return result;
    }

    private Map<String, Object> replayResponse(OperationRecord operation, Map<String, Object> request) {
        return new LinkedHashMap<>(operation.response());
    }

    private CommandOutcome applyCommand(State candidate,
                                        Map<String, Object> command,
                                        long now,
                                        long seq,
                                        String batchId) {
        String type = Json.requireStr(command, "type");
        return switch (type) {
            case "create-context" -> createContext(candidate, command, now, seq, batchId);
            case "add-dating" -> addDating(candidate, command, now, seq, batchId);
            case "add-relation" -> addRelation(candidate, command, now, seq, batchId);
            case "retract-dating" -> retractDating(candidate, command, seq, batchId, now);
            case "retract-relation" -> retractRelation(candidate, command, seq, batchId, now);
            case "create-branch" -> createBranch(candidate, command, now, seq, batchId);
            case "create-snapshot" -> createSnapshot(candidate, command, now, seq, batchId);
            case "merge-branch" -> mergeBranch(candidate, command, now, seq, batchId);
            default -> throw new IllegalArgumentException("unknown command type: " + type);
        };
    }

    private CommandOutcome createContext(State state, Map<String, Object> command, long now, long seq, String batchId) {
        String id = requireId(command, "id");
        if (state.contexts.containsKey(id)) throw new IllegalArgumentException("context already exists: " + id);
        ContextRecord record = new ContextRecord(
                id,
                Json.requireStr(command, "label"),
                Json.str(command, "kind"),
                Json.str(command, "note"),
                now);
        String branchId = branchOrDefault(command, state);
        ensureBranch(state, branchId);
        Map<String, Object> payload = new LinkedHashMap<>(record.toJson());
        payload.put("branchId", branchId);
        Map<String, Object> event = event(seq, batchId, "context-created", now, payload);
        return new CommandOutcome(Map.of("type", "create-context", "id", id, "branchId", branchId),
                List.of(event), List.of(branchId));
    }

    private CommandOutcome addDating(State state, Map<String, Object> command, long now, long seq, String batchId) {
        String id = requireId(command, "id");
        if (state.datingEvidence.containsKey(id)) throw new IllegalArgumentException("dating evidence already exists: " + id);
        String branchId = requireBranch(command, state);
        String contextId = Json.requireStr(command, "contextId");
        if (!state.contexts.containsKey(contextId)) throw new IllegalArgumentException("unknown context: " + contextId);
        if (!state.contextIds(branchId).contains(contextId)) throw new IllegalArgumentException("context is not visible in branch: " + branchId);
        Long lower = Json.integer(command, "lower");
        Long upper = Json.integer(command, "upper");
        boolean lowerOpen = Boolean.TRUE.equals(Json.bool(command, "lowerOpen"));
        boolean upperOpen = Boolean.TRUE.equals(Json.bool(command, "upperOpen"));
        if (lower != null && upper != null) {
            long normalizedLower = lowerOpen ? lower + 1 : lower;
            long normalizedUpper = upperOpen ? upper - 1 : upper;
            if (normalizedLower > normalizedUpper) throw new IllegalArgumentException("dating interval is empty");
        }
        DatingEvidence record = new DatingEvidence(
                id,
                evidenceKey(command, id),
                branchId,
                contextId,
                lower,
                lowerOpen,
                upper,
                upperOpen,
                Json.requireStr(command, "source"),
                Json.str(command, "pages"),
                Json.str(command, "note"),
                Rules.normalizeStrength(Json.str(command, "strength")),
                now);
        Map<String, Object> event = event(seq, batchId, "dating-added", now, record.toJson());
        return new CommandOutcome(Map.of("type", "add-dating", "id", id, "branchId", branchId),
                List.of(event), List.of(branchId));
    }

    private CommandOutcome addRelation(State state, Map<String, Object> command, long now, long seq, String batchId) {
        String id = requireId(command, "id");
        if (state.relationEvidence.containsKey(id)) throw new IllegalArgumentException("relation evidence already exists: " + id);
        String branchId = requireBranch(command, state);
        String from = Json.requireStr(command, "fromContextId");
        String to = Json.requireStr(command, "toContextId");
        if (!state.contexts.containsKey(from) || !state.contexts.containsKey(to)) {
            throw new IllegalArgumentException("relation references unknown context");
        }
        if (!state.contextIds(branchId).contains(from) || !state.contextIds(branchId).contains(to)) {
            throw new IllegalArgumentException("relation references context not visible in branch");
        }
        RelationEvidence record = new RelationEvidence(
                id,
                evidenceKey(command, id),
                branchId,
                from,
                to,
                Rules.normalizeRelation(Json.requireStr(command, "relation")),
                Json.requireStr(command, "source"),
                Json.str(command, "pages"),
                Json.str(command, "note"),
                Rules.normalizeStrength(Json.str(command, "strength")),
                now);
        Map<String, Object> event = event(seq, batchId, "relation-added", now, record.toJson());
        return new CommandOutcome(Map.of("type", "add-relation", "id", id, "branchId", branchId),
                List.of(event), List.of(branchId));
    }

    private CommandOutcome retractDating(State state, Map<String, Object> command, long seq, String batchId, long now) {
        String id = Json.requireStr(command, "evidenceId");
        String branchId = requireBranch(command, state);
        DatingEvidence record = state.datingEvidence.get(id);
        if (record == null) throw new IllegalArgumentException("unknown dating evidence: " + id);
        if (!visibleDating(state, branchId, id)) throw new IllegalArgumentException("dating evidence is not visible in branch");
        if (state.datingRetractions(branchId).contains(id)) throw new IllegalArgumentException("dating evidence already retracted in branch");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branchId", branchId);
        payload.put("evidenceId", id);
        payload.put("reason", Json.str(command, "reason") == null ? "" : Json.str(command, "reason"));
        Map<String, Object> event = event(seq, batchId, "dating-retracted", now, payload);
        return new CommandOutcome(Map.of("type", "retract-dating", "id", id, "branchId", branchId),
                List.of(event), List.of(branchId));
    }

    private CommandOutcome retractRelation(State state, Map<String, Object> command, long seq, String batchId, long now) {
        String id = Json.requireStr(command, "evidenceId");
        String branchId = requireBranch(command, state);
        RelationEvidence record = state.relationEvidence.get(id);
        if (record == null) throw new IllegalArgumentException("unknown relation evidence: " + id);
        if (!visibleRelation(state, branchId, id)) throw new IllegalArgumentException("relation evidence is not visible in branch");
        if (state.relationRetractions(branchId).contains(id)) throw new IllegalArgumentException("relation evidence already retracted in branch");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branchId", branchId);
        payload.put("evidenceId", id);
        payload.put("reason", Json.str(command, "reason") == null ? "" : Json.str(command, "reason"));
        Map<String, Object> event = event(seq, batchId, "relation-retracted", now, payload);
        return new CommandOutcome(Map.of("type", "retract-relation", "id", id, "branchId", branchId),
                List.of(event), List.of(branchId));
    }

    private CommandOutcome createBranch(State state, Map<String, Object> command, long now, long seq, String batchId) {
        String id = requireId(command, "id");
        if (state.branches.containsKey(id)) throw new IllegalArgumentException("branch already exists: " + id);
        String parentId = branchOrDefault(command, state);
        ensureBranch(state, parentId);
        String snapshotId = Json.str(command, "sourceSnapshotId");
        Branch parent = state.branches.get(parentId);
        if (snapshotId != null && !snapshotId.isBlank()) {
            Snapshot snapshot = state.snapshots.get(snapshotId);
            if (snapshot == null) throw new IllegalArgumentException("unknown snapshot: " + snapshotId);
            if (!snapshot.branchId().equals(parentId) && !snapshot.branchId().equals("main")) {
                throw new IllegalArgumentException("snapshot does not belong to the parent branch");
            }
        }
        Branch branch = new Branch(id,
                Json.requireStr(command, "name"),
                parentId,
                snapshotId == null ? "" : snapshotId,
                "working",
                now,
                "");
        State sourceState = (snapshotId != null && !snapshotId.isBlank())
                ? stateAt(state.snapshots.get(snapshotId).eventSeq())
                : state;
        state.contextIds(id).addAll(sourceState.contextIds(parentId));
        state.datingIds(id).addAll(sourceState.datingIds(parentId));
        state.relationIds(id).addAll(sourceState.relationIds(parentId));
        state.datingRetractions(id).addAll(sourceState.datingRetractions(parentId));
        state.relationRetractions(id).addAll(sourceState.relationRetractions(parentId));
        Map<String, Object> event = event(seq, batchId, "branch-created", now, branch.toJson());
        return new CommandOutcome(Map.of("type", "create-branch", "id", id, "parentBranchId", parentId),
                List.of(event), List.of(id));
    }

    private CommandOutcome createSnapshot(State state, Map<String, Object> command, long now, long seq, String batchId) {
        String branchId = requireBranch(command, state);
        String id = requireId(command, "id");
        if (state.snapshots.containsKey(id)) throw new IllegalArgumentException("snapshot already exists: " + id);
        Map<String, Object> analysis = analyzeBranch(state, branchId);
        if (!"feasible".equals(analysis.get("status"))) {
            throw new IllegalArgumentException("cannot freeze a contradictory branch; resolve or retract the reported evidence");
        }
        String hash = Canonical.digest(analysis.get("canonicalInput") == null
                ? Map.of()
                : Json.object(analysis.get("canonicalInput")));
        Snapshot snapshot = new Snapshot(id,
                branchId,
                Json.requireStr(command, "name"),
                now,
                seq,
                hash,
                Json.str(command, "note"));
        Map<String, Object> event = event(seq, batchId, "snapshot-created", now, snapshot.toJson());
        return new CommandOutcome(Map.of("type", "create-snapshot", "id", id, "branchId", branchId, "hash", hash),
                List.of(event), List.of());
    }

    private CommandOutcome mergeBranch(State state, Map<String, Object> command, long now, long seq, String batchId) {
        String sourceId = Json.requireStr(command, "sourceBranchId");
        String targetId = Json.requireStr(command, "targetBranchId");
        if (!state.branches.containsKey(sourceId)) throw new IllegalArgumentException("unknown source branch");
        if (!state.branches.containsKey(targetId)) throw new IllegalArgumentException("unknown target branch");
        if (sourceId.equals(targetId)) throw new IllegalArgumentException("source and target branch must differ");
        List<Map<String, Object>> resolutions = command.get("resolutions") == null
                ? List.of()
                : Json.list(command.get("resolutions")).stream().map(Json::object).toList();
        Map<String, String> resolutionByKey = new LinkedHashMap<>();
        for (Map<String, Object> resolution : resolutions) {
            resolutionByKey.put(Json.requireStr(resolution, "evidenceKey"), Json.requireStr(resolution, "choice"));
        }

        List<Map<String, Object>> conflicts = mergeConflicts(state, sourceId, targetId);
        List<String> unresolved = conflicts.stream()
                .map(conflict -> String.valueOf(conflict.get("evidenceKey")))
                .filter(key -> !resolutionByKey.containsKey(key))
                .toList();
        if (!unresolved.isEmpty()) throw new IllegalArgumentException("manual resolutions are required for: " + unresolved);

        List<Map<String, Object>> payloadContexts = new ArrayList<>();
        List<Map<String, Object>> payloadDating = new ArrayList<>();
        List<Map<String, Object>> payloadRelations = new ArrayList<>();
        List<String> retractedDating = new ArrayList<>();
        List<String> retractedRelations = new ArrayList<>();
        for (Map<String, Object> resolution : resolutions) {
            String key = Json.requireStr(resolution, "evidenceKey");
            if (!"source".equals(Json.requireStr(resolution, "choice"))) continue;
            for (DatingEvidence evidence : visibleDatingList(state, targetId)) {
                if (evidence.evidenceKey().equals(key) && state.datingRetractions(targetId).add(evidence.id())) {
                    retractedDating.add(evidence.id());
                }
            }
            for (RelationEvidence evidence : visibleRelations(state, targetId)) {
                if (evidence.evidenceKey().equals(key) && state.relationRetractions(targetId).add(evidence.id())) {
                    retractedRelations.add(evidence.id());
                }
            }
        }

        for (String contextId : state.contextIds(sourceId)) {
            if (state.contextIds(targetId).contains(contextId)) continue;
            state.contextIds(targetId).add(contextId);
            payloadContexts.add(Map.of("id", contextId));
        }
        for (String evidenceId : state.datingIds(sourceId)) {
            if (state.datingRetractions(sourceId).contains(evidenceId)) continue;
            DatingEvidence source = state.datingEvidence.get(evidenceId);
            if (visibleDating(state, targetId, evidenceId)) continue;
            String choice = resolutionByKey.get(source.evidenceKey());
            if ("target".equals(choice)) continue;
            state.datingIds(targetId).add(evidenceId);
            payloadDating.add(Map.of("id", evidenceId, "evidenceKey", source.evidenceKey(), "choice", choice == null ? "source" : choice));
        }
        for (String evidenceId : state.relationIds(sourceId)) {
            if (state.relationRetractions(sourceId).contains(evidenceId)) continue;
            RelationEvidence source = state.relationEvidence.get(evidenceId);
            if (visibleRelation(state, targetId, evidenceId)) continue;
            String choice = resolutionByKey.get(source.evidenceKey());
            if ("target".equals(choice)) continue;
            state.relationIds(targetId).add(evidenceId);
            payloadRelations.add(Map.of("id", evidenceId, "evidenceKey", source.evidenceKey(), "choice", choice == null ? "source" : choice));
        }
        Branch old = state.branches.get(sourceId);
        Branch merged = new Branch(old.id(), old.name(), old.parentBranchId(), old.sourceSnapshotId(),
                "merged", now, targetId);
        state.branches.put(sourceId, merged);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceBranchId", sourceId);
        payload.put("targetBranchId", targetId);
        payload.put("contextIds", payloadContexts);
        payload.put("datingEvidence", payloadDating);
        payload.put("relationEvidence", payloadRelations);
        payload.put("targetRetractedDating", retractedDating);
        payload.put("targetRetractedRelations", retractedRelations);
        payload.put("resolutions", resolutions);
        payload.put("reason", Json.str(command, "reason") == null ? "" : Json.str(command, "reason"));
        Map<String, Object> event = event(seq, batchId, "branch-merged", now, payload);
        Map<String, Object> statusEvent = event(seq, batchId, "branch-status-changed", now, Map.of(
                "id", sourceId,
                "status", "merged",
                "mergedIntoBranchId", targetId));
        return new CommandOutcome(
                Map.of("type", "merge-branch", "sourceBranchId", sourceId, "targetBranchId", targetId),
                List.of(event, statusEvent), List.of(targetId));
    }

    private List<Map<String, Object>> mergeConflicts(State state, String sourceId, String targetId) {
        Map<String, List<RelationEvidence>> sourceRelations = visibleRelations(state, sourceId).stream()
                .collect(java.util.stream.Collectors.groupingBy(RelationEvidence::evidenceKey,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, List<RelationEvidence>> targetRelations = visibleRelations(state, targetId).stream()
                .collect(java.util.stream.Collectors.groupingBy(RelationEvidence::evidenceKey,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, List<DatingEvidence>> sourceDating = visibleDatingList(state, sourceId).stream()
                .collect(java.util.stream.Collectors.groupingBy(DatingEvidence::evidenceKey,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, List<DatingEvidence>> targetDating = visibleDatingList(state, targetId).stream()
                .collect(java.util.stream.Collectors.groupingBy(DatingEvidence::evidenceKey,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> relationKeys = new java.util.TreeSet<>();
        relationKeys.addAll(sourceRelations.keySet());
        relationKeys.addAll(targetRelations.keySet());
        for (String key : relationKeys) {
            List<RelationEvidence> left = sourceRelations.getOrDefault(key, List.of());
            List<RelationEvidence> right = targetRelations.getOrDefault(key, List.of());
            if (!sameRelationSemantics(left, right)) {
                conflicts.add(Map.of(
                        "evidenceKey", key,
                        "kind", "relation",
                        "sourceEvidenceIds", left.stream().map(RelationEvidence::id).toList(),
                        "targetEvidenceIds", right.stream().map(RelationEvidence::id).toList(),
                        "choices", List.of("source", "target")));
            }
        }
        Set<String> datingKeys = new java.util.TreeSet<>();
        datingKeys.addAll(sourceDating.keySet());
        datingKeys.addAll(targetDating.keySet());
        for (String key : datingKeys) {
            List<DatingEvidence> left = sourceDating.getOrDefault(key, List.of());
            List<DatingEvidence> right = targetDating.getOrDefault(key, List.of());
            if (!sameDatingSemantics(left, right)) {
                conflicts.add(Map.of(
                        "evidenceKey", key,
                        "kind", "dating",
                        "sourceEvidenceIds", left.stream().map(DatingEvidence::id).toList(),
                        "targetEvidenceIds", right.stream().map(DatingEvidence::id).toList(),
                        "choices", List.of("source", "target")));
            }
        }
        return conflicts;
    }

    private boolean sameRelationSemantics(List<RelationEvidence> left, List<RelationEvidence> right) {
        if (left.isEmpty() || right.isEmpty()) return true;
        Set<String> signatures = new HashSet<>();
        for (RelationEvidence evidence : left) {
            signatures.add(evidence.relation() + ":" + evidence.fromContextId() + ":" + evidence.toContextId());
        }
        for (RelationEvidence evidence : right) {
            if (!signatures.contains(evidence.relation() + ":" + evidence.fromContextId() + ":" + evidence.toContextId())) return false;
        }
        return true;
    }

    private boolean sameDatingSemantics(List<DatingEvidence> left, List<DatingEvidence> right) {
        if (left.isEmpty() || right.isEmpty()) return true;
        Set<String> signatures = new HashSet<>();
        for (DatingEvidence evidence : left) {
            signatures.add(evidence.contextId() + ":" + evidence.lower() + ":" + evidence.lowerOpen()
                    + ":" + evidence.upper() + ":" + evidence.upperOpen());
        }
        for (DatingEvidence evidence : right) {
            if (!signatures.contains(evidence.contextId() + ":" + evidence.lower() + ":" + evidence.lowerOpen()
                    + ":" + evidence.upper() + ":" + evidence.upperOpen())) return false;
        }
        return true;
    }

    private State load() {
        State loaded = emptyState();
        for (Path file : store.batchFiles()) {
            Map<String, Object> batch = store.readBatch(file);
            long seq = Json.requireInteger(batch, "seq");
            if (seq != loaded.seq + 1) {
                throw new IllegalStateException("Expected batch " + (loaded.seq + 1) + " but found " + seq);
            }
            String batchId = Json.requireStr(batch, "batchId");
            for (Object rawEvent : Json.list(batch.get("events"))) {
                replayEvent(loaded, Json.object(rawEvent));
            }
            loaded.seq = seq;
        }
        recoverJobs(loaded);
        return loaded;
    }

    private State stateAt(long targetSeq) {
        State replayed = emptyState();
        for (Path file : store.batchFiles()) {
            Map<String, Object> batch = store.readBatch(file);
            long seq = Json.requireInteger(batch, "seq");
            if (seq > targetSeq) break;
            for (Object rawEvent : Json.list(batch.get("events"))) {
                replayEvent(replayed, Json.object(rawEvent));
            }
            replayed.seq = seq;
        }
        return replayed;
    }

    private State emptyState() {
        State initialized = new State();
        Branch main = new Branch("main", "Main", "", "", "working", 0L, "");
        initialized.branches.put("main", main);
        initialized.contextIds("main");
        initialized.datingIds("main");
        initialized.relationIds("main");
        initialized.datingRetractions("main");
        initialized.relationRetractions("main");
        return initialized;
    }

    private State cloneState(State source) {
        return StateCloner.clone(source);
    }

    private void recoverJobs(State recovered) {
        for (JobRecord job : recovered.jobs.values()) {
            if ("queued".equals(job.state()) || "running".equals(job.state())) {
                recovered.jobs.put(job.id(), new JobRecord(job.id(), job.branchId(), job.batchSeq(),
                        "queued", job.attempts(), clock.millis(), "recovered after restart", Map.of()));
            }
        }
    }

    private void replayEvent(State target, Map<String, Object> event) {
        String type = Json.requireStr(event, "type");
        Map<String, Object> payload = Json.object(event.get("payload"));
        switch (type) {
            case "context-created" -> {
                ContextRecord record = ContextRecord.fromJson(payload);
                target.contexts.put(record.id(), record);
                target.contextIds(Json.requireStr(payload, "branchId")).add(record.id());
            }
            case "dating-added" -> {
                DatingEvidence record = DatingEvidence.fromJson(payload);
                target.datingEvidence.put(record.id(), record);
                target.datingIds(record.branchId()).add(record.id());
            }
            case "relation-added" -> {
                RelationEvidence record = RelationEvidence.fromJson(payload);
                target.relationEvidence.put(record.id(), record);
                target.relationIds(record.branchId()).add(record.id());
            }
            case "dating-retracted" -> target.datingRetractions(Json.requireStr(payload, "branchId"))
                    .add(Json.requireStr(payload, "evidenceId"));
            case "relation-retracted" -> target.relationRetractions(Json.requireStr(payload, "branchId"))
                    .add(Json.requireStr(payload, "evidenceId"));
            case "branch-created" -> {
                Branch branch = Branch.fromJson(payload);
                target.branches.put(branch.id(), branch);
                target.contextIds(branch.id());
                target.datingIds(branch.id());
                target.relationIds(branch.id());
                target.datingRetractions(branch.id());
                target.relationRetractions(branch.id());
                target.contextIds(branch.id()).addAll(target.contextIds(branch.parentBranchId() == null
                        || branch.parentBranchId().isBlank() ? "main" : branch.parentBranchId()));
                if (branch.sourceSnapshotId() != null && !branch.sourceSnapshotId().isBlank()) {
                    Snapshot snapshot = target.snapshots.get(branch.sourceSnapshotId());
                    if (snapshot != null) {
                        State historical = stateAt(snapshot.eventSeq());
                        target.contextIds(branch.id()).clear();
                        target.contextIds(branch.id()).addAll(historical.contextIds(branch.parentBranchId()));
                        target.datingIds(branch.id()).addAll(historical.datingIds(branch.parentBranchId()));
                        target.relationIds(branch.id()).addAll(historical.relationIds(branch.parentBranchId()));
                        target.datingRetractions(branch.id()).addAll(historical.datingRetractions(branch.parentBranchId()));
                        target.relationRetractions(branch.id()).addAll(historical.relationRetractions(branch.parentBranchId()));
                    }
                } else {
                    String parent = branch.parentBranchId() == null || branch.parentBranchId().isBlank()
                            ? "main" : branch.parentBranchId();
                    target.datingIds(branch.id()).addAll(target.datingIds(parent));
                    target.relationIds(branch.id()).addAll(target.relationIds(parent));
                    target.datingRetractions(branch.id()).addAll(target.datingRetractions(parent));
                    target.relationRetractions(branch.id()).addAll(target.relationRetractions(parent));
                }
            }
            case "snapshot-created" -> {
                Snapshot snapshot = Snapshot.fromJson(payload);
                target.snapshots.put(snapshot.id(), snapshot);
            }
            case "branch-merged" -> {
                String targetId = Json.requireStr(payload, "targetBranchId");
                for (Object context : Json.list(payload.get("contextIds"))) {
                    target.contextIds(targetId).add(Json.requireStr(Json.object(context), "id"));
                }
                for (Object evidence : Json.list(payload.get("datingEvidence"))) {
                    Map<String, Object> item = Json.object(evidence);
                    target.datingIds(targetId).add(Json.requireStr(item, "id"));
                }
                for (Object evidence : Json.list(payload.get("relationEvidence"))) {
                    Map<String, Object> item = Json.object(evidence);
                    target.relationIds(targetId).add(Json.requireStr(item, "id"));
                }
                for (Object evidenceId : Json.list(payload.getOrDefault("targetRetractedDating", List.of()))) {
                    target.datingRetractions(targetId).add(String.valueOf(evidenceId));
                }
                for (Object evidenceId : Json.list(payload.getOrDefault("targetRetractedRelations", List.of()))) {
                    target.relationRetractions(targetId).add(String.valueOf(evidenceId));
                }
            }
            case "branch-status-changed" -> {
                String id = Json.requireStr(payload, "id");
                Branch old = target.branches.get(id);
                target.branches.put(id, new Branch(old.id(), old.name(), old.parentBranchId(), old.sourceSnapshotId(),
                        Json.requireStr(payload, "status"), old.createdAt(),
                        Json.str(payload, "mergedIntoBranchId")));
            }
            case "job-enqueued" -> target.jobs.put(Json.requireStr(payload, "id"), new JobRecord(
                    Json.requireStr(payload, "id"),
                    Json.requireStr(payload, "branchId"),
                    Json.requireInteger(payload, "batchSeq"),
                    "queued",
                    0,
                    Json.integer(event, "at") == null ? 0L : Json.integer(event, "at"),
                    "",
                    Map.of()));
            case "job-finished" -> {
                JobRecord old = target.jobs.get(Json.requireStr(payload, "id"));
                target.jobs.put(old.id(), new JobRecord(old.id(), old.branchId(), old.batchSeq(),
                        Json.requireStr(payload, "state"),
                        old.attempts() + 1,
                        Json.integer(event, "at") == null ? 0L : Json.integer(event, "at"),
                        Json.str(payload, "error") == null ? "" : Json.str(payload, "error"),
                        payload.get("result") == null ? Map.of() : Json.object(payload.get("result"))));
            }
            case "operation-recorded" -> {
                OperationRecord record = OperationRecord.fromJson(payload);
                target.operations.put(record.idempotencyKey(), record);
                target.seq = record.batchSeq();
            }
            default -> throw new IllegalStateException("unknown event type: " + type);
        }
    }

    private static Map<String, Object> event(long seq, String batchId, String type, long now, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", batchId + ":" + type + ":" + seq);
        event.put("type", type);
        event.put("at", now);
        event.put("payload", payload);
        return event;
    }

    private static String requireId(Map<String, Object> command, String key) {
        String value = Json.requireStr(command, key);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}")) {
            throw new IllegalArgumentException(key + " must contain 1-100 letters, digits, '_', '.', ':' or '-' and start alphanumeric");
        }
        return value;
    }

    private static String evidenceKey(Map<String, Object> command, String fallback) {
        String key = Json.str(command, "evidenceKey");
        if (key == null || key.isBlank()) return fallback;
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")) {
            throw new IllegalArgumentException("evidenceKey must contain 1-200 safe characters");
        }
        return key;
    }

    private static String branchOrDefault(Map<String, Object> command, State state) {
        String branchId = Json.str(command, "branchId");
        if (branchId == null || branchId.isBlank()) return "main";
        ensureBranch(state, branchId);
        return branchId;
    }

    private static String requireBranch(Map<String, Object> command, State state) {
        String branchId = branchOrDefault(command, state);
        ensureBranch(state, branchId);
        if ("merged".equals(state.branches.get(branchId).status())) {
            throw new IllegalArgumentException("branch is merged and read-only: " + branchId);
        }
        return branchId;
    }

    private static void ensureBranch(State state, String branchId) {
        if (!state.branches.containsKey(branchId)) throw new IllegalArgumentException("unknown branch: " + branchId);
    }

    private boolean visibleDating(State state, String branchId, String evidenceId) {
        return state.datingIds(branchId).contains(evidenceId)
                && !state.datingRetractions(branchId).contains(evidenceId);
    }

    private boolean visibleRelation(State state, String branchId, String evidenceId) {
        return state.relationIds(branchId).contains(evidenceId)
                && !state.relationRetractions(branchId).contains(evidenceId);
    }

    private List<DatingEvidence> visibleDatingList(State state, String branchId) {
        return state.datingIds(branchId).stream()
                .filter(id -> !state.datingRetractions(branchId).contains(id))
                .map(state.datingEvidence::get)
                .sorted(Comparator.comparing(DatingEvidence::id))
                .toList();
    }

    private List<RelationEvidence> visibleRelations(State state, String branchId) {
        return state.relationIds(branchId).stream()
                .filter(id -> !state.relationRetractions(branchId).contains(id))
                .map(state.relationEvidence::get)
                .sorted(Comparator.comparing(RelationEvidence::id))
                .toList();
    }

    private List<ContextRecord> visibleContexts(State state, String branchId) {
        return state.contextIds(branchId).stream().sorted().map(state.contexts::get).toList();
    }

    private Map<String, Object> analyzeBranch(State state, String branchId) {
        return Reasoner.analyze(branchId, visibleContexts(state, branchId),
                visibleDatingList(state, branchId), visibleRelations(state, branchId));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CommandOutcome(Map<String, Object> result,
                                  List<Map<String, Object>> events,
                                  List<String> affectedBranches) {
    }
}
