package local.gsb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class StaticAssets {
    private StaticAssets() {
    }

    static String indexHtml() {
        return resource("index.html");
    }

    static String appJs() {
        return resource("app.js");
    }

    static String stylesCss() {
        return resource("styles.css");
    }

    private static String resource(String name) {
        try {
            return new String(StaticAssets.class.getResourceAsStream("/" + name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing static resource " + name, ex);
        }
    }
}
