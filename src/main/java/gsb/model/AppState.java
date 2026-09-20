package gsb.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppState {
    public static final String RULE_VERSION = "rules-v1.0";
    public static final String FORMAT_VERSION = "pair-wise-gsb-v1";

    public final Map<String, BranchState> branches = new LinkedHashMap<>();
    public final Map<String, BranchInfo> branchInfos = new LinkedHashMap<>();
    public final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    public final Map<String, JobRecord> jobs = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> idempotency = new LinkedHashMap<>();
    public final List<Map<String, Object>> events = new ArrayList<>();

    public AppState() {
        long now = System.currentTimeMillis();
        BranchState main = new BranchState();
        Map<String, Object> rule = Json.object();
        rule.put("version", RULE_VERSION);
        rule.put("activatedAt", now);
        rule.put("description", "Stratigraphic partial order and open/closed integer date constraints");
        main.rules.add(rule);
        branches.put("main", main);
        branchInfos.put("main", new BranchInfo("main", "Main", "open", null, null, null, now));
    }

    public BranchState branch(String id) {
        BranchState state = branches.get(id);
        if (state == null) throw new IllegalArgumentException("Unknown branch: " + id);
        return state;
    }

    public BranchInfo info(String id) {
        BranchInfo info = branchInfos.get(id);
        if (info == null) throw new IllegalArgumentException("Unknown branch: " + id);
        return info;
    }
}
