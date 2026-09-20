package gsb.model;

import java.util.Map;

public record Interval(long lower, boolean lowerOpen, long upper, boolean upperOpen) {
    public Interval {
        if (lower == 0 || upper == 0) {
            throw new IllegalArgumentException("Dating intervals cannot contain or end at year zero");
        }
        if (lower > upper) {
            throw new IllegalArgumentException("Interval lower bound is after upper bound");
        }
        if (lower == upper && (lowerOpen || upperOpen)) {
            throw new IllegalArgumentException("An integer interval with equal bounds must be closed at both ends");
        }
        long normalizedLower = lower + (lowerOpen ? 1 : 0);
        long normalizedUpper = upper - (upperOpen ? 1 : 0);
        if (normalizedLower == 0 || normalizedUpper == 0 || normalizedLower > normalizedUpper) {
            throw new IllegalArgumentException("Open integer interval is empty or crosses forbidden year zero");
        }
    }

    public static Interval parse(Map<String, Object> map) {
        long lower = asLong(Json.required(map, "lower"));
        long upper = asLong(Json.required(map, "upper"));
        boolean lowerOpen = Json.optionalBool(map, "lowerOpen", false);
        boolean upperOpen = Json.optionalBool(map, "upperOpen", false);
        if (map.containsKey("lowerEra")) lower = YearPoint.fromInput(Json.string(map, "lowerEra"), lower);
        if (map.containsKey("upperEra")) upper = YearPoint.fromInput(Json.string(map, "upperEra"), upper);
        return new Interval(lower, lowerOpen, upper, upperOpen);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.object();
        map.put("lower", lower);
        map.put("lowerInclusive", !lowerOpen);
        map.put("lowerOpen", lowerOpen);
        map.put("lowerDisplay", YearPoint.display(lower));
        map.put("upper", upper);
        map.put("upperInclusive", !upperOpen);
        map.put("upperOpen", upperOpen);
        map.put("upperDisplay", YearPoint.display(upper));
        return map;
    }

    public static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalArgumentException("Expected integer year");
    }
}
