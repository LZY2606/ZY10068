package gsb.store;

final class IntervalCodec {
    private IntervalCodec() {}
    static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        return Long.parseLong(String.valueOf(value));
    }
}
