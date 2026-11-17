package minidb.cli;

import java.nio.file.Path;
import minidb.engine.Database;
import minidb.sql.Parser;
import minidb.sql.Ast.Statement;

/** CrashTest가 띄우는 자식 JVM: 여러 페이지짜리 UPDATE를 끝없이 커밋하며 번호를 한 줄씩 찍는다 (부모가 강제 종료) */
public final class CrashChild {

    static final int ROWS = 200;

    public static void main(String[] args) throws Exception {
        try (Database db = Database.open(Path.of(args[0]), false)) {
            db.execute(Parser.parse("CREATE TABLE t (v INT, pad VARCHAR(200));").get(0));
            StringBuilder sql = new StringBuilder("INSERT INTO t VALUES ");
            for (int i = 0; i < ROWS; i++) sql.append(i == 0 ? "" : ", ").append("(0, '").append("p".repeat(150)).append("')");
            for (Statement s : Parser.parse(sql + ";")) db.execute(s);
            System.out.println("READY");
            System.out.flush();
            for (int k = 1; ; k++) {
                db.execute(Parser.parse("UPDATE t SET v = " + k + ";").get(0));
                System.out.println(k);
                System.out.flush();
            }
        }
    }
}
