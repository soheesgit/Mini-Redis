package minidb.cli;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import minidb.cache.RedisCache;
import minidb.engine.Database;
import minidb.engine.Result;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Ast.Statement;
import minidb.sql.Parser;
import minidb.sql.SqlException;
import minidb.storage.StorageException;
import minidb.type.Values;

/**
 * 명령줄 진입점: {@code [--data-dir DIR] [--fsync=off] [file.sql]}. 파일이 없으면 표준 입력을 읽는다.
 * 먼저 전체를 파싱해서 문법 오류가 하나라도 있으면 아무것도 실행하지 않는다. 실행 중 첫 에러에서 멈추며
 * 그 앞 문장은 이미 커밋된 채로 남는다 (문장마다 커밋). 종료 코드: 0 성공, 1 에러.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, new InputStreamReader(System.in, StandardCharsets.UTF_8), System.out, System.err));
    }

    public static int run(String[] args, Reader in, PrintStream out, PrintStream err) {
        Path dir = Path.of("data");
        boolean fsync = true;
        String file = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--data-dir") && i + 1 < args.length) dir = Path.of(args[++i]);
            else if (a.startsWith("--data-dir=")) dir = Path.of(a.substring("--data-dir=".length()));
            else if (a.equals("--fsync=off")) fsync = false;
            else if (a.startsWith("--") || file != null) return fail(err, "알 수 없는 인자: " + a);
            else file = a;
        }
        try {
            String sql;
            if (file != null) {
                sql = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            } else {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                for (int n; (n = in.read(buf)) >= 0; ) sb.append(buf, 0, n);
                sql = sb.toString();
            }
            List<Statement> statements = Parser.parse(sql);
            try (Database db = Database.openCached(dir, fsync, RedisCache.fromEnvironment())) {
                for (Statement st : statements) print(db, st, db.execute(st), out);
                if (db.cacheSummary() != null) err.println(db.cacheSummary());
            }
            return 0;
        } catch (SqlException | StorageException | IOException e) {
            return fail(err, e.getMessage());
        }
    }

    private static int fail(PrintStream err, String msg) {
        err.println("ERROR " + msg);
        return 1;
    }

    private static void print(Database db, Statement st, Result r, PrintStream out) {
        if (r.columns() != null) {
            out.println(String.join("\t", r.columns()));
            for (Object[] row : r.rows()) {
                List<String> cells = new ArrayList<>();
                for (Object v : row) cells.add(Values.format(v, db.zone()));
                out.println(String.join("\t", cells));
            }
            out.println("(" + r.rows().size() + " rows)");
        } else if (st instanceof CreateTable) {
            out.println("OK");
        } else {
            out.println("OK, " + r.affected() + " rows affected");
        }
    }
}
