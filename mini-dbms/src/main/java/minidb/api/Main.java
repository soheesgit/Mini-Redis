package minidb.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import minidb.engine.Database;

/** DBMS HTTP 서버 진입점. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            launch(Options.parse(args));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("ERROR " + e.getMessage());
            System.exit(1);
        }
    }

    private static void launch(Options options) throws IOException {
        Database db = Database.open(options.dataDir(), options.fsync());
        QueryServer server;
        try {
            server = QueryServer.start(options.address(), db, options.threads());
        } catch (IOException | RuntimeException | Error e) {
            try (db) {
                throw e;
            }
        }

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (db; server) {
                    // 리소스 선언의 역순으로 서버를 비운 뒤 DB를 닫는다.
                } catch (IOException e) {
                    System.err.println("ERROR " + e.getMessage());
                }
            }, "minidb-shutdown"));
        } catch (RuntimeException | Error e) {
            try (db; server) {
                throw e;
            }
        }
        System.out.println("Mini DBMS API listening on " + server.address());
    }

    private record Options(InetSocketAddress address, Path dataDir, int threads, boolean fsync) {
        static Options parse(String[] args) {
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8091);
            Path dataDir = Path.of("data");
            int threads = 8;
            boolean fsync = true;
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                switch (option) {
                    case "--addr" -> address = parseAddress(value(args, ++i, option));
                    case "--data-dir" -> dataDir = Path.of(value(args, ++i, option));
                    case "--threads" -> threads = Integer.parseInt(value(args, ++i, option));
                    case "--fsync=off" -> fsync = false;
                    default -> throw new IllegalArgumentException("알 수 없는 인자: " + option);
                }
            }
            return new Options(address, dataDir, threads, fsync);
        }

        private static String value(String[] args, int i, String option) {
            if (i == args.length || args[i].startsWith("--")) {
                throw new IllegalArgumentException(option + "에 값이 필요합니다");
            }
            return args[i];
        }

        private static InetSocketAddress parseAddress(String text) {
            int colon = text.lastIndexOf(':');
            if (colon <= 0 || colon == text.length() - 1) {
                throw new IllegalArgumentException("--addr는 HOST:PORT 형식이어야 합니다");
            }
            return new InetSocketAddress(text.substring(0, colon), Integer.parseInt(text.substring(colon + 1)));
        }
    }
}
