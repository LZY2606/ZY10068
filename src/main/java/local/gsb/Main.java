package local.gsb;

import java.nio.file.Path;
import java.time.Clock;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 5204;
        Path dataDirectory = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            if ("--data".equals(args[i]) && i + 1 < args.length) dataDirectory = Path.of(args[++i]);
        }
        AppService service = new AppService(dataDirectory, Clock.systemUTC());
        service.recover();
        WebServer server = WebServer.start(service, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            service.stop();
        }));
        System.out.println("Pair-wise GSB running at http://127.0.0.1:" + server.port());
    }
}
