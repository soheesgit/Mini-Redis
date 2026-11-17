package minidbms.cli;

import minidbms.cache.RedisCache;
import minidbms.engine.SqlEngine;
import minidbms.sql.SqlException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.StringJoiner;

/** SQL 파일을 읽어 실행기에 넘기고, 실행 결과를 터미널에 출력한다. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    // 출력 스트림을 인자로 받아 테스트에서 JVM을 종료하지 않고 출력과 종료 코드를 확인할 수 있다.
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("--help")) {
            printUsage(out);
            return 0;
        }

        boolean customDirectory = args.length == 3 && args[0].equals("--data-dir");
        if (!customDirectory && (args.length != 1 || args[0].startsWith("--"))) {
            printUsage(err);
            return 2;
        }
        String sqlFile = args[args.length - 1];

        String sql;
        try {
            sql = Files.readString(Path.of(sqlFile), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException exception) {
            err.println("Cannot read SQL file: " + exception.getMessage());
            return 1;
        }

        // 종료 코드 2는 명령이나 SQL 입력 오류, 1은 파일 읽기 또는 저장 오류를 뜻한다.
        try {
            Path dataDirectory = Path.of(customDirectory ? args[1] : "data");
            // MINIDBMS_REDIS(예: http://127.0.0.1:8080)가 있으면 Mini Redis를 캐시로 쓴다
            var engine = new SqlEngine(dataDirectory, RedisCache.fromEnvironment());
            engine.execute(sql, result -> printResult(result, out));
            if (engine.cacheSummary() != null) {
                err.println(engine.cacheSummary()); // 결과(stdout)와 섞이지 않게 stderr로
            }
            return 0;
        } catch (SqlException | InvalidPathException exception) {
            err.println(exception.getMessage());
            return 2;
        } catch (IOException exception) {
            err.println("Database error: " + exception.getMessage());
            return 1;
        }
    }

    private static void printResult(SqlEngine.Result result, PrintStream out) {
        switch (result) {
            case SqlEngine.Inserted inserted -> out.println("INSERT 1 (id=" + inserted.id() + ")");
            case SqlEngine.Selected selected -> {
                out.println(String.join("\t", selected.columns()));
                for (var row : selected.rows()) {
                    var line = new StringJoiner("\t");
                    // 값 안의 탭과 줄바꿈이 열과 행의 구분자로 보이지 않도록 이스케이프한다.
                    for (Object value : row) {
                        line.add(value.toString().replace("\\", "\\\\")
                                .replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n"));
                    }
                    out.println(line);
                }
            }
        }
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: java -cp out/main minidbms.cli.Main [--data-dir <directory>] <sql-file>");
    }
}
