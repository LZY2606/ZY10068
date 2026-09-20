package gsb.infer;

import gsb.model.BranchState;
import gsb.model.ContextInfo;
import gsb.model.Dating;
import gsb.model.Evidence;

import java.util.List;

public record InferenceView(List<ContextInfo> contexts, List<Evidence> evidences, List<Dating> datings) {
    public static InferenceView of(BranchState state) {
        return new InferenceView(
                state.contexts.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList(),
                state.evidences.values().stream().filter(item -> !item.retracted()).sorted((a, b) -> a.id().compareTo(b.id())).toList(),
                state.datings.values().stream().filter(item -> !item.retracted()).sorted((a, b) -> a.id().compareTo(b.id())).toList());
    }
}
