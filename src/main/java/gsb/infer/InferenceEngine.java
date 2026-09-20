package gsb.infer;

import gsb.model.Conflict;
import gsb.model.Dating;
import gsb.model.Evidence;
import gsb.model.Json;
import gsb.model.RelationType;
import gsb.model.YearPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

public final class InferenceEngine {
    private static final String L_SUFFIX = "#lower";
    private static final String U_SUFFIX = "#upper";
    private static final String SOURCE = "__difference_source__";

    public InferenceResult analyze(InferenceView view) {
        List<Constraint> constraints = buildConstraints(view);
        BellmanFordResult bf = BellmanFordResult.run(constraints, variables(view));
        if (!bf.feasible) {
            List<String> sourceCandidates = bf.cycle.stream().map(edge -> edge.sourceId()).distinct().sorted().toList();
            List<Constraint> coreConstraints = minimizeCore(constraints, sourceCandidates, variables(view));
            List<String> evidenceIds = coreConstraints.stream().filter(item -> item.kind().startsWith("relation.")).map(item -> item.sourceId()).distinct().sorted().toList();
            List<String> datingIds = coreConstraints.stream().filter(item -> item.kind().startsWith("dating.")).map(item -> item.sourceId()).distinct().sorted().toList();
            List<String> cycle = bf.cycle.stream().map(edge -> edge.from() + " -> " + edge.to()).toList();
            String message = "Date or ordering constraints form a positive cycle; removing any listed source breaks this contradiction.";
            Conflict conflict = new Conflict("UNSATISFIABLE_CONSTRAINTS", message, evidenceIds, datingIds, cycle, details(bf));
            return InferenceResult.conflict(conflict, constraints, describeOrderCycle(bf));
        }

        InferenceResult result = InferenceResult.feasible(constraints);
        addIntervals(result, view, bf.dist);
        addPartialOrder(result, view);
        return result;
    }

    private List<Constraint> buildConstraints(InferenceView view) {
        List<Constraint> constraints = new ArrayList<>();
        for (var context : view.contexts()) {
            constraints.add(new Constraint(context.id() + L_SUFFIX, context.id() + U_SUFFIX, 0,
                    "__context__" + context.id(), "context.internal", context.id() + " lower bound is not above upper bound"));
        }
        for (Dating dating : view.datings()) {
            long lower = dating.interval().lower() + (dating.interval().lowerOpen() ? 1 : 0);
            long upper = dating.interval().upper() - (dating.interval().upperOpen() ? 1 : 0);
            if (lower == 0 || upper == 0) {
                throw new IllegalArgumentException("Open endpoint resolves to forbidden year zero for " + dating.id());
            }
            String lowerVariable = dating.context() + L_SUFFIX;
            String upperVariable = dating.context() + U_SUFFIX;
            constraints.add(new Constraint(SOURCE, lowerVariable, lower, dating.id(), "dating.lower", dating.context() + " earliest allowed integer year"));
            constraints.add(new Constraint(upperVariable, SOURCE, -upper, dating.id(), "dating.upper", dating.context() + " latest allowed integer year"));
        }
        for (Evidence evidence : view.evidences()) {
            if (evidence.relation().contemporaneous()) {
                String a = evidence.source();
                String b = evidence.target();
                constraints.add(new Constraint(a + L_SUFFIX, b + U_SUFFIX, 0, evidence.id(), "relation.contemporaneous.overlap", a + " starts no later than " + b + " ends"));
                constraints.add(new Constraint(b + L_SUFFIX, a + U_SUFFIX, 0, evidence.id(), "relation.contemporaneous.overlap", b + " starts no later than " + a + " ends"));
            } else {
                RelationType.DirectedOrder order = evidence.relation().directedOrder(evidence.source(), evidence.target()).orElseThrow();
                String earlier = order.earlier();
                String later = order.later();
                constraints.add(new Constraint(earlier + U_SUFFIX, later + L_SUFFIX, strictIntegerGap(evidence.relation()), evidence.id(),
                        "relation." + evidence.relation().wire(), earlier + " strictly before " + later));
            }
        }
        return constraints;
    }

    private long strictIntegerGap(RelationType type) {
        return 1;
    }

    private Set<String> variables(InferenceView view) {
        Set<String> variables = new LinkedHashSet<>();
        variables.add(SOURCE);
        view.contexts().forEach(item -> {
            variables.add(item.id() + L_SUFFIX);
            variables.add(item.id() + U_SUFFIX);
        });
        return variables;
    }

    private void addIntervals(InferenceResult result, InferenceView view, Map<String, Long> ignored) {
        List<Constraint> constraints = buildConstraints(view).stream()
                .filter(item -> !item.kind().equals("context.internal")).toList();
        Set<String> variables = variables(view);
        Map<String, Long> lowerBounds = longestFromSource(constraints, variables);
        Map<String, Long> upperBounds = shortestFromSource(constraints, variables);
        for (var context : view.contexts()) {
            Map<String, Object> map = result.freshMap();
            Long lower = lowerBounds.get(context.id() + L_SUFFIX);
            Long upper = upperBounds.get(context.id() + U_SUFFIX);
            boolean bounded = lower != null && upper != null;
            map.put("contextId", context.id());
            map.put("bounded", bounded);
            if (bounded) {
                if (lower == 0 || upper == 0 || lower > upper) {
                    throw new IllegalStateException("Invalid propagated interval for " + context.id());
                }
                map.put("lower", lower);
                map.put("upper", upper);
                map.put("lowerDisplay", YearPoint.display(lower));
                map.put("upperDisplay", YearPoint.display(upper));
                map.put("lowerInclusive", true);
                map.put("upperInclusive", true);
            } else {
                map.put("lower", lower);
                map.put("upper", upper);
                map.put("note", "No complete finite propagated interval");
            }
            result.intervalsMap().add(map);
        }
    }

    private Map<String, Long> longestFromSource(List<Constraint> constraints, Set<String> variables) {
        Map<String, Long> dist = new HashMap<>();
        dist.put(SOURCE, 0L);
        for (int iteration = 0; iteration < variables.size(); iteration++) {
            boolean changed = false;
            for (Constraint edge : constraints) {
                Long from = dist.get(edge.from());
                if (from == null) continue;
                long candidate = Math.addExact(from, edge.weight());
                Long current = dist.get(edge.to());
                if (current == null || candidate > current) {
                    dist.put(edge.to(), candidate);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return dist;
    }

    private Map<String, Long> shortestFromSource(List<Constraint> constraints, Set<String> variables) {
        Map<String, Long> dist = new HashMap<>();
        dist.put(SOURCE, 0L);
        for (int iteration = 0; iteration < variables.size(); iteration++) {
            boolean changed = false;
            for (Constraint edge : constraints) {
                Long to = dist.get(edge.to());
                if (to == null) continue;
                long candidate = Math.addExact(to, -edge.weight());
                Long current = dist.get(edge.from());
                if (current == null || candidate < current) {
                    dist.put(edge.from(), candidate);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return dist;
    }

    private void addPartialOrder(InferenceResult result, InferenceView view) {
        Map<String, GraphEdge> direct = new LinkedHashMap<>();
        Map<String, Set<String>> contemporaneous = new HashMap<>();
        Set<String> contextIds = new LinkedHashSet<>();
        view.contexts().forEach(item -> contextIds.add(item.id()));
        for (Evidence evidence : view.evidences()) {
            if (evidence.relation().contemporaneous()) {
                contemporaneous.computeIfAbsent(evidence.source(), ignored -> new LinkedHashSet<>()).add(evidence.target());
                contemporaneous.computeIfAbsent(evidence.target(), ignored -> new LinkedHashSet<>()).add(evidence.source());
                GraphEdge edge = edge(direct, evidence.source(), evidence.target());
                edge.evidenceIds.add(evidence.id());
                edge.strongest = strongest(edge.strongest, evidence.relation());
            } else {
                RelationType.DirectedOrder directed = evidence.relation().directedOrder(evidence.source(), evidence.target()).orElseThrow();
                GraphEdge edge = edge(direct, directed.earlier(), directed.later());
                edge.evidenceIds.add(evidence.id());
                edge.strongest = strongest(edge.strongest, evidence.relation());
            }
        }

        int n = contextIds.size();
        Map<String, Integer> index = new HashMap<>();
        List<String> ids = new ArrayList<>(contextIds);
        for (int i = 0; i < ids.size(); i++) index.put(ids.get(i), i);
        boolean[][] reach = new boolean[n][n];
        String[][] via = new String[n][n];
        for (GraphEdge edge : direct.values()) {
            if (!edge.to.equals(edge.from) && !edge.strongest.contemporaneous()) {
                Integer from = index.get(edge.from);
                Integer to = index.get(edge.to);
                if (from != null && to != null) {
                    reach[from][to] = true;
                    via[from][to] = edge.from + " > " + edge.to;
                }
            }
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (reach[i][k] && reach[k][j] && !reach[i][j]) {
                        reach[i][j] = true;
                        via[i][j] = via[i][k] + " ; " + via[k][j];
                    }
                }
            }
        }

        List<GraphEdge> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (reach[i][j]) {
                    GraphEdge edge = direct.get(ids.get(i) + "\0" + ids.get(j));
                    boolean isDirect = edge != null;
                    GraphEdge output = isDirect ? edge : new GraphEdge(ids.get(i), ids.get(j));
                    result.edgesMap().add(output.toJson(!isDirect, via[i][j]));
                    all.add(output);
                }
            }
        }
        direct.values().stream().filter(edge -> edge.strongest.contemporaneous()).sorted(Comparator.comparing((GraphEdge edge) -> edge.from).thenComparing(edge -> edge.to))
                .forEach(edge -> result.edgesMap().add(edge.toJson(false, null)));

        List<Set<String>> components = components(contextIds, contemporaneous);
        components.stream().sorted(Comparator.comparing(component -> component.stream().sorted().findFirst().orElse("")))
                .map(component -> component.stream().sorted().toList())
                .forEach(result.groups()::add);

        result.setTopologicalOrder(stableOrder(ids, components, direct, reach, index));
    }

    private GraphEdge edge(Map<String, GraphEdge> edges, String from, String to) {
        return edges.computeIfAbsent(from + "\0" + to, ignored -> new GraphEdge(from, to));
    }

    private RelationType strongest(RelationType current, RelationType candidate) {
        if (current == null) return candidate;
        if (candidate.contemporaneous()) return current;
        if (current.contemporaneous()) return candidate;
        return current.ordinal() <= candidate.ordinal() ? current : candidate;
    }

    private List<Set<String>> components(Set<String> ids, Map<String, Set<String>> ties) {
        Map<String, String> parent = new HashMap<>();
        ids.forEach(id -> parent.put(id, id));
        ties.forEach((left, neighbours) -> neighbours.forEach(right -> union(parent, left, right)));
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        ids.stream().sorted().forEach(id -> grouped.computeIfAbsent(find(parent, id), ignored -> new LinkedHashSet<>()).add(id));
        return new ArrayList<>(grouped.values());
    }

    private String find(Map<String, String> parent, String id) {
        String current = id;
        while (!current.equals(parent.get(current))) {
            parent.put(current, parent.get(parent.get(current)));
            current = parent.get(current);
        }
        return current;
    }

    private void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) parent.put(rootB, rootA);
    }

    private List<String> stableOrder(List<String> allNodes, List<Set<String>> components, Map<String, GraphEdge> direct,
                                     boolean[][] reach, Map<String, Integer> index) {
        Map<String, Integer> componentOf = new HashMap<>();
        List<Set<String>> sortedComponents = components.stream()
                .sorted(Comparator.comparing(component -> component.stream().sorted().findFirst().orElse("")))
                .toList();
        for (int i = 0; i < sortedComponents.size(); i++) {
            for (String node : sortedComponents.get(i)) componentOf.put(node, i);
        }
        int componentCount = sortedComponents.size();
        Set<Integer>[] outgoing = new Set[componentCount];
        int[] indegree = new int[componentCount];
        for (int i = 0; i < componentCount; i++) outgoing[i] = new HashSet<>();
        for (GraphEdge edge : direct.values()) {
            if (edge.strongest.contemporaneous()) continue;
            int a = componentOf.get(edge.from);
            int b = componentOf.get(edge.to);
            if (a != b && outgoing[a].add(b)) indegree[b]++;
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>(Comparator.comparing(componentIndex -> sortedComponents.get(componentIndex).stream().sorted().findFirst().orElse("")));
        for (int i = 0; i < componentCount; i++) if (indegree[i] == 0) ready.add(i);
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            int component = ready.poll();
            order.addAll(sortedComponents.get(component).stream().sorted().toList());
            for (int next : outgoing[component]) {
                indegree[next]--;
                if (indegree[next] == 0) ready.add(next);
            }
        }
        return order;
    }

    private List<Constraint> minimizeCore(List<Constraint> constraints, List<String> candidates, Set<String> variables) {
        Set<String> retained = new LinkedHashSet<>(candidates);
        for (String candidate : candidates) {
            List<Constraint> filtered = constraints.stream().filter(item -> !candidate.equals(item.sourceId())).toList();
            BellmanFordResult result = BellmanFordResult.run(filtered, variables);
            if (result.feasible) retained.add(candidate);
            else retained.remove(candidate);
        }
        return constraints.stream().filter(item -> retained.contains(item.sourceId())).toList();
    }

    private Map<String, Object> details(BellmanFordResult bf) {
        Map<String, Object> map = Json.object();
        List<Object> cycle = Json.array();
        bf.cycle.forEach(edge -> cycle.add(edge.toJson()));
        map.put("cycleConstraints", cycle);
        return map;
    }

    private List<String> describeOrderCycle(BellmanFordResult bf) {
        Set<String> nodes = new LinkedHashSet<>();
        bf.cycle.forEach(edge -> {
            nodes.add(edge.from().replace(L_SUFFIX, "").replace(U_SUFFIX, ""));
            nodes.add(edge.to().replace(L_SUFFIX, "").replace(U_SUFFIX, ""));
        });
        return new ArrayList<>(nodes);
    }

    private record BellmanFordResult(boolean feasible, Map<String, Long> dist, List<Constraint> cycle) {
        private static BellmanFordResult run(List<Constraint> input, Set<String> variables) {
            List<Constraint> edges = input.stream().sorted(Comparator.comparing((Constraint edge) -> edge.sourceId())
                    .thenComparing(edge -> edge.from()).thenComparing(edge -> edge.to())).toList();
            Map<String, Long> dist = new HashMap<>();
            Map<String, Constraint> predecessor = new HashMap<>();
            variables.forEach(variable -> dist.put(variable, 0L));
            String changed = null;
            for (int iteration = 0; iteration < variables.size(); iteration++) {
                changed = null;
                for (Constraint edge : edges) {
                    if (!variables.contains(edge.from()) || !variables.contains(edge.to())) continue;
                    long candidate = Math.addExact(dist.get(edge.from()), edge.weight());
                    if (candidate > dist.get(edge.to())) {
                        dist.put(edge.to(), candidate);
                        predecessor.put(edge.to(), edge);
                        changed = edge.to();
                    }
                }
                if (changed == null) return new BellmanFordResult(true, Map.copyOf(dist), List.of());
            }
            if (changed == null) return new BellmanFordResult(true, Map.copyOf(dist), List.of());
            String node = changed;
            for (int i = 0; i < variables.size(); i++) {
                Constraint edge = predecessor.get(node);
                if (edge == null) break;
                node = edge.from();
            }
            List<Constraint> cycle = new ArrayList<>();
            String current = node;
            Set<String> visited = new HashSet<>();
            while (true) {
                Constraint edge = predecessor.get(current);
                if (edge == null) break;
                cycle.add(0, edge);
                current = edge.from();
                if (current.equals(node) || !visited.add(current)) break;
            }
            return new BellmanFordResult(false, Map.of(), cycle);
        }
    }
}
