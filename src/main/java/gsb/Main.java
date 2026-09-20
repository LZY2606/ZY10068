package gsb;

import gsb.store.Store;
import gsb.web.HttpServer;
import gsb.web.Service;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 5204;
        Path data = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            if ("--data".equals(args[i]) && i + 1 < args.length) data = Path.of(args[++i]);
        }
        Store store = new Store(data);
        Service service = new Service(store);
        HttpServer server = new HttpServer(service);
        server.start(port);
        System.out.println("Stratigraphic sequence server: http://127.0.0.1:" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            service.close();
        }));
    }
}
