package gsb.store;

import gsb.model.BranchState;
import gsb.model.ContextInfo;
import gsb.model.Dating;
import gsb.model.Evidence;
import gsb.model.Json;

import java.util.Map;

public final class ProjectionCodec {
    private ProjectionCodec() {}

    public static Map<String, Object> toMap(BranchState state) {
        return state.toJson();
    }

    public static BranchState fromMap(Map<String, Object> map) {
        BranchState state = new BranchState();
        for (Object item : Json.array(map.get("contexts"))) {
            Map<String, Object> object = Json.object(item);
            long createdAt = IntervalCodec.asLong(object.getOrDefault("createdAt", 0L));
            ContextInfo info = new ContextInfo(Json.string(object, "id"),
                    Json.optionalString(object, "label", Json.string(object, "id")),
                    Json.optionalString(object, "description", ""), createdAt);
            state.contexts.put(info.id(), info);
        }
        for (Object item : Json.array(map.get("evidences"))) {
            Map<String, Object> object = Json.object(item);
            Evidence evidence = Evidence.parse(object, Json.string(object, "id"), IntervalCodec.asLong(object.getOrDefault("createdAt", 0L)));
            if (Json.optionalBool(object, "retracted", false)) {
                evidence = evidence.retract(IntervalCodec.asLong(object.getOrDefault("retractedAt", 0L)),
                        Json.optionalString(object, "retractionReason", ""));
            }
            state.evidences.put(evidence.id(), evidence);
        }
        for (Object item : Json.array(map.get("datings"))) {
            Map<String, Object> object = Json.object(item);
            Dating dating = Dating.parse(object, Json.string(object, "id"), IntervalCodec.asLong(object.getOrDefault("createdAt", 0L)));
            if (Json.optionalBool(object, "retracted", false)) {
                dating = dating.retract(IntervalCodec.asLong(object.getOrDefault("retractedAt", 0L)),
                        Json.optionalString(object, "retractionReason", ""));
            }
            state.datings.put(dating.id(), dating);
        }
        Object rules = map.get("rules");
        if (rules != null) state.rules.addAll(Json.array(rules).stream().map(Json::object).toList());
        Object eventIds = map.get("eventIds");
        if (eventIds != null) state.eventIds.addAll(Json.array(eventIds).stream().map(String::valueOf).toList());
        return state;
    }
}
