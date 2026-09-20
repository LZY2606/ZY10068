package gsb.infer;

import gsb.model.RelationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class GraphEdge {
    final String from;
    final String to;
    final List<String> evidenceIds = new ArrayList<>();
    RelationType strongest;

    GraphEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    Map<String, Object> toJson(boolean transitive, String via) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("from", from);
        map.put("to", to);
        map.put("basis", transitive ? "transitive" : "direct");
        map.put("evidenceIds", evidenceIds);
        if (transitive) map.put("transitivePath", via);
        if (strongest != null) map.put("relation", strongest.wire());
        return map;
    }
}
