package gsb;

import gsb.tests.CoreTests;
import gsb.tests.StoreAndServiceTests;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class TestRunner {
    public static void main(String[] args) {
        List<Class<?>> suites = List.of(CoreTests.class, StoreAndServiceTests.class);
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Class<?> suite : suites) {
            for (Method method : suite.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !method.getName().startsWith("test")) continue;
                try {
                    Object instance = suite.getDeclaredConstructor().newInstance();
                    Method setup = suite.getDeclaredMethod("setup");
                    setup.setAccessible(true);
                    setup.invoke(instance);
                    method.setAccessible(true);
                    method.invoke(instance);
                    passed++;
                    System.out.println("PASS " + suite.getSimpleName() + "." + method.getName());
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    failures.add(suite.getSimpleName() + "." + method.getName() + ": " + cause);
                    cause.printStackTrace();
                }
            }
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) System.exit(1);
    }
}
