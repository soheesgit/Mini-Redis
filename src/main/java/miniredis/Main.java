package miniredis;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import miniredis.engine.Engine;
import miniredis.httpapi.HttpApi;
import miniredis.store.Store;

/**
 * 저장소 서버 실행.
 * java -cp target/classes miniredis.Main [--addr 127.0.0.1:8080] [--dir ./data] [--maxmemory 바이트] [--policy noeviction]
 * 비밀번호는 환경변수 MINIREDIS_PASSWORD에서만 읽는다.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String addr = "127.0.0.1:8080", dir = "./data", policy = "noeviction";
        long maxmemory = 0;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--addr" -> addr = args[i + 1];
                case "--dir" -> dir = args[i + 1];
                case "--maxmemory" -> maxmemory = Long.parseLong(args[i + 1]);
                case "--policy" -> policy = args[i + 1];
                default -> fail("알 수 없는 옵션: " + args[i]);
            }
        }
        int colon = addr.lastIndexOf(':');
        InetSocketAddress bind = new InetSocketAddress(addr.substring(0, colon), Integer.parseInt(addr.substring(colon + 1)));
        String password = System.getenv("MINIREDIS_PASSWORD");
        if (password != null && password.isEmpty()) password = null;
        if (!bind.getAddress().isLoopbackAddress() && password == null) {
            fail("외부 주소에 바인딩하려면 MINIREDIS_PASSWORD가 필요하다");
        }

        Engine engine;
        try {
            engine = Engine.start(new Engine.Config(Path.of(dir), maxmemory, Store.Policy.parse(policy)));
        } catch (Exception e) {
            fail(e.getMessage());
            return;
        }
        HttpServer server = HttpApi.start(bind, engine, password);
        System.out.println("mini-redis listening on " + addr + " (data: " + dir + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try {
                engine.close(); // AOF force 후 잠금 해제. kill -9에서는 실행되지 않는다
            } catch (InterruptedException ignored) {
                // 종료 중
            }
        }));
    }

    private static void fail(String msg) {
        System.err.println("시작 거부: " + msg);
        System.exit(1);
    }
}
