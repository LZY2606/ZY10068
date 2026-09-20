package local.gsb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Reasoner {
    private final List<ContextRecord> contexts;
    private final List<DatingEvidence> datingEvidence;
    private final List<RelationEvidence> relationEvidence;

    private Reasoner(List<ContextRecord> contexts,
                     List<DatingEvidence> datingEvidence,
                     List<RelationEvidence> relationEvidence) {
        this.contexts = contexts;
        this.datingEvidence = datingEvidence;
        this.relationEvidence = relationEvidence;
    }

    static Map<String, Object> analyze(String branchId,
                                       List<ContextRecord> contexts,
                                       List<DatingEvidence> datingEvidence,
                                       List<RelationEvidence> relationEvidence) {
        return new Reasoner(contexts, datingEvidence, relationEvidence).run(branchId);
    }

    private Map<String, Object> run(String branchId) {
        List<ContextRecord> sortedContexts = contexts.stream()
                .sorted(Comparator.comparing(ContextRecord::id))
                .toList();
        List<DatingEvidence> sortedDating = evidenceOrder(datingEvidence);
        List<RelationEvidence> sortedRelations = evidenceOrder(relationEvidence);
        List<String> contextIds = sortedContexts.stream().map(ContextRecord::id).toList();

        UnionFind unionFind = new UnionFind(contextIds);
        for (RelationEvidence evidence : sortedRelations) {
            if ("contemporaneous".equals(evidence.relation())
                    && !evidence.fromContextId().equals(evidence.toContextId())) {
                unionFind.union(evidence.fromContextId(), evidence.toContextId());
            }
        }

        Map<String, Integer> rootOrder = new LinkedHashMap<>();
        Map<String, List<String>> membersByRoot = new LinkedHashMap<>();
        for (String contextId : contextIds) {
            String root = unionFind.find(contextId);
            rootOrder.putIfAbsent(root, rootOrder.size());
            membersByRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(contextId);
        }
        List<String> roots = new ArrayList<>(membersByRoot.keySet());
        roots.sort(Comparator.naturalOrder());

        Map<String, List<RelationEvidence>> directEvidenceByPair = new HashMap<>();
        for (RelationEvidence evidence : sortedRelations) {
            if (!"earlier-than".equals(evidence.relation())) continue;
            String fromRoot = unionFind.find(evidence.fromContextId());
            String toRoot = unionFind.find(evidence.toContextId());
            if (fromRoot.equals(toRoot)) {
                return contradiction(branchId, sortedContexts, sortedDating, sortedRelations,
                        "topological-cycle",
                        List.of(List.of(evidence.id())),
                        "An earlier-than relation closes inside a contemporaneous component.");
            }
            String key = fromRoot + "\u0000" + toRoot;
            directEvidenceByPair.computeIfAbsent(key, ignored -> new ArrayList<>()).add(evidence);
        }

        int componentCount = roots.size();
        Map<String, Integer> componentIndex = new HashMap<>();
        for (int i = 0; i < roots.size(); i++) componentIndex.put(roots.get(i), i);
        List<List<Integer>> adjacency = new ArrayList<>();
        List<List<Integer>> reverse = new ArrayList<>();
        for (int i = 0; i < componentCount; i++) {
            adjacency.add(new ArrayList<>());
            reverse.add(new ArrayList<>());
        }
        for (Map.Entry<String, List<RelationEvidence>> entry : directEvidenceByPair.entrySet()) {
            String[] pair = entry.getKey().split("\u0000", 2);
            int from = componentIndex.get(pair[0]);
            int to = componentIndex.get(pair[1]);
            if (!adjacency.get(from).contains(to)) {
                adjacency.get(from).add(to);
                reverse.get(to).add(from);
            }
        }
        for (List<Integer> neighbors : adjacency) neighbors.sort(Integer::compareTo);
        for (List<Integer> neighbors : reverse) neighbors.sort(Integer::compareTo);

        List<Integer> topological = topologicalSort(adjacency);
        if (topological == null) return topologicalCycle(branchId, sortedContexts, sortedDating, sortedRelations);

        TimeModel timeModel = buildTimeModel(roots, topological, unionFind, sortedRelations);
        if (timeModel.conflict != null) {
            return contradiction(branchId, sortedContexts, sortedDating, sortedRelations,
                    "empty-interval",
                    List.of(new ArrayList<>(timeModel.conflict)),
                    "The date constraints and strict relations have an empty intersection.");
        }

        Closure closure = Closure.of(adjacency, topological, directEvidenceByPair, roots);
        Map<String, Object> result = baseResult(branchId, sortedContexts, sortedDating, sortedRelations);
        result.put("status", "feasible");
        result.put("stableTopologicalOrder", stableContextOrder(topological, roots, membersByRoot));
        result.put("components", componentResults(roots, membersByRoot, timeModel, adjacency, directEvidenceByPair));
        result.put("relations", relationResults(roots, membersByRoot, unionFind, closure, componentIndex, directEvidenceByPair));
        result.put("directEdgeCount", closure.directCount);
        result.put("transitiveEdgeCount", closure.transitiveCount);
        result.put("canonicalInput", canonicalInput(sortedContexts, sortedDating, sortedRelations));
        result.put("canonicalDigest", Canonical.digest(canonicalInput(sortedContexts, sortedDating, sortedRelations)));
        return result;
    }

    private TimeModel buildTimeModel(List<String> roots,
                                     List<Integer> topological,
                                     UnionFind unionFind,
                                     List<RelationEvidence> allRelations) {
        int componentCount = roots.size();
        int variableCount = 1 + componentCount * 2;
        long infinity = 1_000_000_000_000L;
        long[][] distances = new long[variableCount][variableCount];
        for (long[] row : distances) java.util.Arrays.fill(row, infinity);
        for (int i = 0; i < variableCount; i++) distances[i][i] = 0;
        List<Set<String>> edgeEvidence = new ArrayList<>();
        for (int i = 0; i < variableCount * variableCount; i++) edgeEvidence.add(new LinkedHashSet<>());

        for (int component = 0; component < componentCount; component++) {
            int lower = lowerVariable(component);
            int upper = upperVariable(component);
            addEdge(distances, edgeEvidence, upper, lower, 0, Set.of());
        }
        for (DatingEvidence evidence : datingEvidence) {
            int component = roots.indexOf(unionFind.find(evidence.contextId()));
            int lower = lowerVariable(component);
            int upper = upperVariable(component);
            Long low = normalizedLower(evidence);
            Long high = normalizedUpper(evidence);
            if (low != null && high != null && low > high) {
                TimeModel conflict = new TimeModel();
                conflict.conflict = List.of(evidence.id());
                return conflict;
            }
            if (low != null) addEdge(distances, edgeEvidence, lower, 0, -low, Set.of(evidence.id()));
            if (high != null) addEdge(distances, edgeEvidence, 0, upper, high, Set.of(evidence.id()));
        }
        for (RelationEvidence evidence : allRelations) {
            if (!"earlier-than".equals(evidence.relation())) continue;
            int earlier = roots.indexOf(unionFind.find(evidence.fromContextId()));
            int later = roots.indexOf(unionFind.find(evidence.toContextId()));
            addEdge(distances, edgeEvidence, lowerVariable(later), upperVariable(earlier), -1, Set.of(evidence.id()));
        }

        floyd(distances);
        for (int i = 0; i < variableCount; i++) {
            if (distances[i][i] < 0) {
                TimeModel model = new TimeModel();
                model.conflict = minimalTemporalEvidence(contexts, datingEvidence, allRelations);
                return model;
            }
        }
        TimeModel model = new TimeModel();
        for (int component = 0; component < componentCount; component++) {
            String root = roots.get(component);
            long lowerDistance = distances[lowerVariable(component)][0];
            long upperDistance = distances[0][upperVariable(component)];
            Long lower = lowerDistance < infinity / 2 ? -lowerDistance : null;
            Long upper = upperDistance < infinity / 2 ? upperDistance : null;
            if (lower != null && upper != null && lower > upper) {
                model.conflict = minimalTemporalEvidence(contexts, datingEvidence, allRelations);
                return model;
            }
            model.lower.put(root, lower);
            model.upper.put(root, upper);
        }
        return model;
    }

    private static int lowerVariable(int component) {
        return 1 + component * 2;
    }

    private static int upperVariable(int component) {
        return 2 + component * 2;
    }

    private static Long normalizedLower(DatingEvidence evidence) {
        if (evidence.lower() == null) return null;
        return evidence.lowerOpen() ? evidence.lower() + 1 : evidence.lower();
    }

    private static Long normalizedUpper(DatingEvidence evidence) {
        if (evidence.upper() == null) return null;
        return evidence.upperOpen() ? evidence.upper() - 1 : evidence.upper();
    }

    private static void addEdge(long[][] distances,
                                List<Set<String>> edgeEvidence,
                                int from,
                                int to,
                                long weight,
                                Set<String> evidenceIds) {
        if (weight < distances[from][to]) {
            distances[from][to] = weight;
            edgeEvidence.set(from * distances.length + to, new LinkedHashSet<>(evidenceIds));
        }
    }

    private static void floyd(long[][] distances) {
        for (int k = 0; k < distances.length; k++) {
            for (int i = 0; i < distances.length; i++) {
                for (int j = 0; j < distances.length; j++) {
                    if (distances[i][k] + distances[k][j] < distances[i][j]) {
                        distances[i][j] = distances[i][k] + distances[k][j];
                    }
                }
            }
        }
    }

    private static List<String> minimalTemporalEvidence(List<ContextRecord> contexts,
                                                        List<DatingEvidence> dating,
                                                        List<RelationEvidence> relations) {
        List<String> ids = new ArrayList<>();
        dating.stream().map(DatingEvidence::id).forEach(ids::add);
        relations.stream().map(RelationEvidence::id).forEach(ids::add);
        List<String> working = new ArrayList<>(ids);
        for (String candidate : new ArrayList<>(working)) {
            List<DatingEvidence> trialDating = dating.stream()
                    .filter(evidence -> working.contains(evidence.id()) && !evidence.id().equals(candidate))
                    .toList();
            List<RelationEvidence> trialRelations = relations.stream()
                    .filter(evidence -> working.contains(evidence.id()) && !evidence.id().equals(candidate))
                    .toList();
            if (!temporalConflict(contexts, trialDating, trialRelations)) continue;
            working.remove(candidate);
        }
        working.sort(String::compareTo);
        return working;
    }

    private static boolean temporalConflict(List<ContextRecord> contexts,
                                            List<DatingEvidence> dating,
                                            List<RelationEvidence> relations) {
        List<String> contextIds = contexts.stream().map(ContextRecord::id).sorted().toList();
        UnionFind unionFind = new UnionFind(contextIds);
        relations.stream().filter(evidence -> "contemporaneous".equals(evidence.relation()))
                .forEach(evidence -> unionFind.union(evidence.fromContextId(), evidence.toContextId()));
        List<String> roots = new ArrayList<>();
        for (String contextId : contextIds) {
            String root = unionFind.find(contextId);
            if (!roots.contains(root)) roots.add(root);
        }
        roots.sort(String::compareTo);
        int count = roots.size();
        int variables = 1 + count * 2;
        long infinity = 1_000_000_000_000L;
        long[][] distances = new long[variables][variables];
        for (long[] row : distances) java.util.Arrays.fill(row, infinity);
        for (int i = 0; i < variables; i++) distances[i][i] = 0;
        for (int component = 0; component < count; component++) {
            distances[upperVariable(component)][lowerVariable(component)] = 0;
        }
        for (DatingEvidence evidence : dating) {
            int component = roots.indexOf(unionFind.find(evidence.contextId()));
            Long lower = normalizedLower(evidence);
            Long upper = normalizedUpper(evidence);
            if (lower != null && upper != null && lower > upper) return true;
            if (lower != null) distances[lowerVariable(component)][0] = Math.min(distances[lowerVariable(component)][0], -lower);
            if (upper != null) distances[0][upperVariable(component)] = Math.min(distances[0][upperVariable(component)], upper);
        }
        for (RelationEvidence evidence : relations) {
            if (!"earlier-than".equals(evidence.relation())) continue;
            int earlier = roots.indexOf(unionFind.find(evidence.fromContextId()));
            int later = roots.indexOf(unionFind.find(evidence.toContextId()));
            if (earlier == later) return true;
            distances[lowerVariable(later)][upperVariable(earlier)] = -1;
        }
        floyd(distances);
        for (int i = 0; i < variables; i++) if (distances[i][i] < 0) return true;
        return false;
    }

    private static final class TimeModel {
        private final Map<String, Long> lower = new LinkedHashMap<>();
        private final Map<String, Long> upper = new LinkedHashMap<>();
        private List<String> conflict;
    }

    private static List<String> stableContextOrder(List<Integer> topological,
                                                   List<String> roots,
                                                   Map<String, List<String>> membersByRoot) {
        List<String> result = new ArrayList<>();
        for (int index : topological) {
            result.addAll(membersByRoot.get(roots.get(index)).stream().sorted().toList());
        }
        return result;
    }

    private List<Object> componentResults(List<String> roots,
                                          Map<String, List<String>> membersByRoot,
                                          TimeModel timeModel,
                                          List<List<Integer>> adjacency,
                                          Map<String, List<RelationEvidence>> directEvidenceByPair) {
        List<Object> result = new ArrayList<>();
        for (String root : roots) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("id", root);
            component.put("contextIds", membersByRoot.get(root).stream().sorted().toList());
            component.put("allowedInterval", intervalJson(timeModel.lower.get(root), timeModel.upper.get(root)));
            result.add(component);
        }
        return result;
    }

    private static Map<String, Object> intervalJson(Long lower, Long upper) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lower", lower);
        result.put("lowerOpen", false);
        result.put("upper", upper);
        result.put("upperOpen", false);
        result.put("yearConvention", "astronomical signed integer; 1 BCE is 0, 2 BCE is -1");
        return result;
    }

    private List<Object> relationResults(List<String> roots,
                                         Map<String, List<String>> membersByRoot,
                                         UnionFind unionFind,
                                         Closure closure,
                                         Map<String, Integer> componentIndex,
                                         Map<String, List<RelationEvidence>> directEvidenceByPair) {
        List<Object> result = new ArrayList<>();
        for (int from = 0; from < roots.size(); from++) {
            for (int to = 0; to < roots.size(); to++) {
                if (!closure.reachable[from][to] || from == to) continue;
                List<String> pairKey = List.of(roots.get(from), roots.get(to));
                List<RelationEvidence> direct = directEvidenceByPair.get(pairKey.get(0) + "\u0000" + pairKey.get(1));
                boolean isDirect = direct != null;
                Map<String, Object> relation = new LinkedHashMap<>();
                relation.put("fromComponentId", roots.get(from));
                relation.put("toComponentId", roots.get(to));
                relation.put("relation", "earlier-than");
                relation.put("kind", isDirect ? "direct" : "transitive");
                relation.put("fromContextIds", membersByRoot.get(roots.get(from)).stream().sorted().toList());
                relation.put("toContextIds", membersByRoot.get(roots.get(to)).stream().sorted().toList());
                relation.put("directEvidenceIds", isDirect
                ? direct.stream().map(RelationEvidence::id).sorted().toList()
                : List.of());
                relation.put("pathEvidenceIds", closure.paths[from][to]);
                result.add(relation);
            }
        }
        addContemporaneousRelations(result, roots, membersByRoot, unionFind);
        return result;
    }

    private void addContemporaneousRelations(List<Object> result,
                                             List<String> roots,
                                             Map<String, List<String>> membersByRoot,
                                             UnionFind unionFind) {
        Map<String, List<RelationEvidence>> byPair = new LinkedHashMap<>();
        for (RelationEvidence evidence : relationEvidence) {
            if (!"contemporaneous".equals(evidence.relation())) continue;
            if (evidence.fromContextId().equals(evidence.toContextId())) continue;
            String left = min(evidence.fromContextId(), evidence.toContextId());
            String right = max(evidence.fromContextId(), evidence.toContextId());
            byPair.computeIfAbsent(left + "\u0000" + right, ignored -> new ArrayList<>()).add(evidence);
        }
        byPair.forEach((key, evidence) -> {
            String[] pair = key.split("\u0000", 2);
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("fromComponentId", pair[0]);
            relation.put("toComponentId", pair[1]);
            relation.put("relation", "contemporaneous");
            relation.put("kind", "direct");
            relation.put("fromContextIds", membersByRoot.get(unionFind.find(pair[0])).stream().sorted().toList());
            relation.put("toContextIds", membersByRoot.get(unionFind.find(pair[1])).stream().sorted().toList());
            relation.put("directEvidenceIds", evidence.stream().map(RelationEvidence::id).sorted().toList());
            relation.put("pathEvidenceIds", evidence.stream().map(RelationEvidence::id).sorted().toList());
            result.add(relation);
        });
    }

    private static String min(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String max(String left, String right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private Map<String, Object> baseResult(String branchId,
                                           List<ContextRecord> sortedContexts,
                                           List<DatingEvidence> sortedDating,
                                           List<RelationEvidence> sortedRelations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branchId", branchId);
        result.put("ruleVersion", Rules.VERSION);
        result.put("contexts", sortedContexts.stream().map(ContextRecord::toJson).toList());
        result.put("datingEvidence", sortedDating.stream().map(DatingEvidence::toJson).toList());
        result.put("relationEvidence", sortedRelations.stream().map(RelationEvidence::toJson).toList());
        return result;
    }

    private Map<String, Object> contradiction(String branchId,
                                              List<ContextRecord> sortedContexts,
                                              List<DatingEvidence> sortedDating,
                                              List<RelationEvidence> sortedRelations,
                                              String type,
                                              List<List<String>> evidenceSets,
                                              String message) {
        Map<String, Object> result = baseResult(branchId, sortedContexts, sortedDating, sortedRelations);
        result.put("status", "contradiction");
        Map<String, Object> contradiction = new LinkedHashMap<>();
        contradiction.put("type", type);
        contradiction.put("message", message);
        contradiction.put("minimalEvidenceSets", evidenceSets);
        result.put("contradiction", contradiction);
        return result;
    }

    private Map<String, Object> canonicalInput(List<ContextRecord> sortedContexts,
                                               List<DatingEvidence> sortedDating,
                                               List<RelationEvidence> sortedRelations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formatVersion", 1);
        result.put("ruleVersion", Rules.VERSION);
        result.put("contexts", sortedContexts.stream().map(ContextRecord::toJson).toList());
        result.put("datingEvidence", sortedDating.stream().map(DatingEvidence::toJson).toList());
        result.put("relationEvidence", sortedRelations.stream().map(RelationEvidence::toJson).toList());
        return result;
    }

    private static final class Closure {
        private final boolean[][] reachable;
        private final List<String>[][] paths;
        private int directCount;
        private int transitiveCount;

        @SuppressWarnings("unchecked")
        private Closure(int count) {
            reachable = new boolean[count][count];
            paths = (List<String>[][]) new List[count][count];
        }

        private static Closure of(List<List<Integer>> adjacency,
                                  List<Integer> topological,
                                  Map<String, List<RelationEvidence>> directEvidenceByPair,
                                  List<String> roots) {
            int count = adjacency.size();
            Closure closure = new Closure(count);
            for (int position = topological.size() - 1; position >= 0; position--) {
                int from = topological.get(position);
                for (int to = 0; to < count; to++) {
                    List<String>[] toNode = closure.paths[from];
                    if (from == to) {
                        closure.reachable[from][to] = true;
                        toNode[to] = List.of();
                        continue;
                    }
                    for (int middle : adjacency.get(from)) {
                        if (!closure.reachable[middle][to]) continue;
                        List<RelationEvidence> edgeEvidence =
                                directEvidenceByPair.get(roots.get(from) + "\u0000" + roots.get(middle));
                        String edgeId = edgeEvidence.stream().map(RelationEvidence::id).min(String::compareTo).orElseThrow();
                        List<String> candidate = new ArrayList<>();
                        candidate.add(edgeId);
                        candidate.addAll(closure.paths[middle][to]);
                        candidate = candidate.stream().distinct().sorted().toList();
                        if (!closure.reachable[from][to]) {
                            closure.reachable[from][to] = true;
                            toNode[to] = candidate;
                        } else {
                            toNode[to] = choosePath(toNode[to], candidate);
                        }
                    }
                    if (closure.reachable[from][to]) {
                        if (!adjacency.get(from).contains(to)) closure.transitiveCount++;
                        else closure.directCount++;
                    }
                }
            }
            return closure;
        }

        private static List<String> choosePath(List<String> current, List<String> candidate) {
            if (current == null || candidate.size() < current.size()) return candidate;
            if (candidate.size() == current.size() && candidate.toString().compareTo(current.toString()) < 0) return candidate;
            return current;
        }
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        private UnionFind(List<String> ids) {
            ids.forEach(id -> parent.put(id, id));
        }

        private String find(String id) {
            String current = id;
            while (!current.equals(parent.get(current))) current = parent.get(current);
            String root = current;
            current = id;
            while (!current.equals(root)) {
                String next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }

        private void union(String left, String right) {
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (!leftRoot.equals(rightRoot)) parent.put(max(leftRoot, rightRoot), min(leftRoot, rightRoot));
        }
    }

    private static <T extends Record> List<T> evidenceOrder(List<T> evidence) {
        return evidence.stream()
                .sorted(Comparator.comparing(item -> {
                    if (item instanceof RelationEvidence relation) return relation.id();
                    if (item instanceof DatingEvidence dating) return dating.id();
                    return item.toString();
                }))
                .toList();
    }

    private static List<Integer> topologicalSort(List<List<Integer>> adjacency) {
        int count = adjacency.size();
        int[] indegree = new int[count];
        for (List<Integer> neighbors : adjacency) {
            for (int to : neighbors) indegree[to]++;
        }
        List<Integer> queue = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (indegree[i] == 0) queue.add(i);
        }
        List<Integer> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            queue.sort(Integer::compareTo);
            int node = queue.remove(0);
            result.add(node);
            for (int to : adjacency.get(node)) {
                indegree[to]--;
                if (indegree[to] == 0) queue.add(to);
            }
        }
        return result.size() == count ? result : null;
    }

    private Map<String, Object> topologicalCycle(String branchId,
                                                 List<ContextRecord> sortedContexts,
                                                 List<DatingEvidence> sortedDating,
                                                 List<RelationEvidence> sortedRelations) {
        List<String> minimal = minimalTopologicalEvidence(sortedRelations, contexts.stream().map(ContextRecord::id).toList());
        return contradiction(branchId, sortedContexts, sortedDating, sortedRelations,
                "topological-cycle",
                List.of(minimal),
                "Earlier-than relations form a directed cycle.");
    }

    private static List<String> minimalTopologicalEvidence(List<RelationEvidence> evidence, List<String> contextIds) {
        List<RelationEvidence> working = new ArrayList<>(evidence);
        for (RelationEvidence candidate : new ArrayList<>(working)) {
            List<RelationEvidence> trial = new ArrayList<>(working);
            trial.remove(candidate);
            if (!topologicalConflict(trial, contextIds)) continue;
            working = trial;
        }
        return working.stream().map(RelationEvidence::id).sorted().toList();
    }

    private static boolean topologicalConflict(List<RelationEvidence> evidence, List<String> contextIds) {
        UnionFind unionFind = new UnionFind(contextIds);
        for (RelationEvidence candidate : evidence) {
            if ("contemporaneous".equals(candidate.relation())) {
                unionFind.union(candidate.fromContextId(), candidate.toContextId());
            }
        }
        Map<String, Integer> index = new HashMap<>();
        for (String contextId : contextIds) {
            String root = unionFind.find(contextId);
            index.putIfAbsent(root, index.size());
        }
        List<List<Integer>> graph = emptyGraph(index.size());
        Set<String> pairs = new HashSet<>();
        for (RelationEvidence candidate : evidence) {
            if (!"earlier-than".equals(candidate.relation())) continue;
            int from = index.get(unionFind.find(candidate.fromContextId()));
            int to = index.get(unionFind.find(candidate.toContextId()));
            if (from == to) return true;
            String pair = from + "->" + to;
            if (pairs.add(pair)) graph.get(from).add(to);
        }
        return topologicalSort(graph) == null;
    }

    private static List<List<Integer>> emptyGraph(int count) {
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i < count; i++) graph.add(new ArrayList<>());
        return graph;
    }
}
