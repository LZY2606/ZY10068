package gsb.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public enum RelationType {
    EARLIER_THAN("earlier-than", true, false, false),
    LATER_THAN("later-than", false, true, false),
    CUT("cuts", false, true, false),
    CUT_BY("cut-by", true, false, false),
    SEALS("seals", false, true, false),
    SEALED_BY("sealed-by", true, false, false),
    CONTEMPORANEOUS("contemporaneous", false, false, true);

    private final String wire;
    private final boolean forwardEarlier;
    private final boolean reverseEarlier;
    private final boolean contemporaneous;

    RelationType(String wire, boolean forwardEarlier, boolean reverseEarlier, boolean contemporaneous) {
        this.wire = wire;
        this.forwardEarlier = forwardEarlier;
        this.reverseEarlier = reverseEarlier;
        this.contemporaneous = contemporaneous;
    }

    public String wire() {
        return wire;
    }

    public boolean contemporaneous() {
        return contemporaneous;
    }

    public Optional<DirectedOrder> directedOrder(String source, String target) {
        if (forwardEarlier) return Optional.of(new DirectedOrder(source, target));
        if (reverseEarlier) return Optional.of(new DirectedOrder(target, source));
        return Optional.empty();
    }

    public static RelationType parse(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("relation must be a string");
        }
        for (RelationType type : values()) {
            if (type.wire.equals(text) || type.name().equalsIgnoreCase(text)) return type;
        }
        throw new IllegalArgumentException("Unsupported relation: " + text + "; allowed values are " + Set.of(values()));
    }

    public Map<String, Object> toJson() {
        return Map.of("id", name(), "name", wire);
    }

    public record DirectedOrder(String earlier, String later) {}
}
