package gsb.tests;

public final class Asserts {
    private Asserts() {}

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    static void assertContains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("Expected text to contain <" + needle + "> in <" + haystack + ">");
        }
    }
}
