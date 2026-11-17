package minidbms.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 별도 JVM으로 CLI를 실행해 SQL 입력부터 파일 저장과 조회까지 검증한다. */
public final class Phase1IntegrationTest {
    private record Run(int code, String stdout, String stderr) {}

    private Phase1IntegrationTest() {}

    public static void main(String[] args) throws Exception {
        // 삽입·조회 시나리오에서 만든 임시 DB로 오류 처리도 검증한다.
        Path workspace = Files.createTempDirectory("mini-dbms-phase1-");
        try {
            verifiesPersistence(workspace);
            rejectsInvalidQueries(workspace);
            reportsCorruptRecords(workspace);
            verifiesMutations(Files.createDirectory(workspace.resolve("mutations")));
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        System.out.println("Passed 4 Phase 1 integration scenarios.");
    }

    private static void verifiesPersistence(Path workspace) throws Exception {
        Path table = workspace.resolve("data/users.tbl");
        expectSuccess(run(workspace, "SELECT * FROM users;"), "id\tname\temail\n");
        if (Files.exists(table)) {
            throw new AssertionError("Reading an empty table should not create a file");
        }

        expectSuccess(run(workspace, """
                INSERT INTO users (email, name) VALUES ('sohee@example.com', '소희');
                INSERT INTO users (name, email) VALUES ('O''Brien; Jr.', 'obrien@example.com');
                SELECT * FROM users;
                """), """
                INSERT 1 (id=1)
                INSERT 1 (id=2)
                id\tname\temail
                1\t소희\tsohee@example.com
                2\tO'Brien; Jr.\tobrien@example.com
                """);

        // 새 프로세스가 기존 파일에서 조회하고, SELECT가 파일 바이트를 바꾸지 않는지 확인한다.
        byte[] beforeSelect = Files.readAllBytes(table);
        expectSuccess(run(workspace, """
                SELECT name, id FROM users WHERE id = 2;
                SELECT email FROM users WHERE name = '소희';
                SELECT id FROM users WHERE email = 'missing@example.com';
                """, "--data-dir", "data"), """
                name\tid
                O'Brien; Jr.\t2
                email
                sohee@example.com
                id
                """);
        expectSameBytes(beforeSelect, table);

        expectSuccess(run(workspace, "SELECT id FROM users;", "--data-dir", "another-database"), "id\n");
        // 재실행 후 첫 INSERT가 1로 초기화되지 않고 기존 마지막 ID의 다음 번호를 쓰는지 검증한다.
        expectSuccess(run(workspace, "INSERT INTO users (name, email) VALUES ('New\tLine\nHere', '');"),
                "INSERT 1 (id=3)\n");
        expectSuccess(run(workspace, "SELECT name, email FROM users WHERE id = 3;"),
                "name\temail\nNew\\tLine\\nHere\t\n");
    }

    private static void rejectsInvalidQueries(Path workspace) throws Exception {
        Path table = workspace.resolve("data/users.tbl");
        byte[] original = Files.readAllBytes(table);
        expectError(run(workspace, "SELECT * FROM missing;"), 2, "", "Unknown table 'missing'");
        expectError(run(workspace, "SELECT unknown FROM users;"), 2, "", "Unknown column 'unknown'");
        expectError(run(workspace, "SELECT id FROM users WHERE unknown = 1;"), 2, "", "Unknown column 'unknown'");
        expectError(run(workspace, "SELECT id FROM users WHERE id = '1';"), 2, "", "requires BIGINT");
        expectError(run(workspace, "SELECT id FROM users WHERE email = 1;"), 2, "", "requires TEXT");
        expectError(run(workspace, "INSERT INTO users (name, email) VALUES ('x');"), 2, "", "counts differ");
        expectError(run(workspace, "INSERT INTO users (name) VALUES ('x');"), 2, "", "Missing INSERT column 'email'");
        expectError(run(workspace, "INSERT INTO users (name, name) VALUES ('first', 'second');"),
                2, "", "Duplicate INSERT column 'name'");
        expectError(run(workspace, "INSERT INTO users (id, name, email) VALUES (1, 'x', 'x');"),
                2, "", "generated automatically");
        expectError(run(workspace, "INSERT INTO users (name, email) VALUES (1, 'x');"), 2, "", "requires TEXT");

        // 두 번째 문장의 구문 오류는 실행 시작 전에, 없는 테이블 오류는 첫 INSERT가 끝난 뒤에 발견된다.
        String insert = "INSERT INTO users (name, email) VALUES ('committed', 'x');";
        expectError(run(workspace, insert + " SELECT * FROM"), 2, "", "Expected an identifier");
        expectSameBytes(original, table);

        expectError(run(workspace, insert + " SELECT * FROM missing;"), 2,
                "INSERT 1 (id=4)\n", "Unknown table 'missing'");
        expectSuccess(run(workspace, "SELECT name FROM users WHERE id = 4;"), "name\ncommitted\n");
    }

    private static void reportsCorruptRecords(Path workspace) throws Exception {
        Path table = workspace.resolve("data/users.tbl");
        long validSize = Files.size(table);
        // 정상 데이터 뒤에 1바이트만 붙여 다음 레코드의 4바이트 길이 필드가 잘린 상황을 만든다.
        Files.write(table, new byte[] {0}, StandardOpenOption.APPEND);
        byte[] corrupt = Files.readAllBytes(table);

        expectError(run(workspace, "SELECT * FROM users;"), 1, "",
                "at byte " + validSize + ": incomplete record length");
        expectError(run(workspace, "INSERT INTO users (name, email) VALUES ('x', 'x');"), 1, "",
                "at byte " + validSize + ": incomplete record length");
        expectSameBytes(corrupt, table);

        // 원래 길이로 돌린 뒤 이번에는 길이 필드가 온전하고 본문만 잘린 레코드를 만든다.
        Files.write(table, Arrays.copyOf(corrupt, (int) validSize));
        Files.write(table, new byte[] {0, 0, 0, 16, 0}, StandardOpenOption.APPEND);
        expectError(run(workspace, "SELECT * FROM users;"), 1, "",
                "at byte " + validSize + ": incomplete record");
    }

    private static void verifiesMutations(Path workspace) throws Exception {
        Path table = Files.createDirectory(workspace.resolve("data")).resolve("users.tbl");
        // 변경 레코드 도입 전 형식으로 저장한 ID 1, name=first, email=a@x.
        byte[] oldRecord = HexFormat.of().parseHex("00000018000000000000000100000005666972737400000003614078");
        Files.write(table, oldRecord);

        expectSuccess(run(workspace, """
                INSERT INTO users (name, email) VALUES ('second', 'b@x');
                INSERT INTO users (name, email) VALUES ('third', 'c@x');
                UPDATE users SET name = '두 번째' WHERE id = 2;
                UPDATE users SET email = 'longer@example.com' WHERE id = 2;
                DELETE FROM users WHERE id = 3;
                SELECT id, name FROM users WHERE id >= 1 AND id <= 3 AND name != 'first';
                """), """
                INSERT 1 (id=2)
                INSERT 1 (id=3)
                UPDATE 1
                UPDATE 1
                DELETE 1
                id\tname
                2\t두 번째
                """);
        byte[] changed = Files.readAllBytes(table);
        if (!Arrays.equals(oldRecord, Arrays.copyOf(changed, oldRecord.length))) {
            throw new AssertionError("Mutations changed the bytes of the legacy record");
        }

        expectSuccess(run(workspace, """
                SELECT * FROM users WHERE id > 0 AND id < 4 AND name <> '';
                UPDATE users SET name = 'missing' WHERE id = 3;
                DELETE FROM users WHERE id = 3;
                DELETE FROM users WHERE id = 99;
                """), """
                id\tname\temail
                1\tfirst\ta@x
                2\t두 번째\tlonger@example.com
                UPDATE 0
                DELETE 0
                DELETE 0
                """);
        expectError(run(workspace, "UPDATE users SET id = 6 WHERE id = 2;"), 2, "", "cannot be updated");
        expectError(run(workspace, "UPDATE users SET name = 6 WHERE id = 2;"), 2, "", "requires TEXT");
        expectError(run(workspace, "UPDATE users SET missing = 'x' WHERE id = 2;"), 2, "", "Unknown column");
        expectError(run(workspace, "DELETE FROM users;"), 2, "", "Expected WHERE");
        expectError(run(workspace, "SELECT id FROM users WHERE name > 'a';"), 2, "", "TEXT comparisons");
        expectError(run(workspace, "SELECT id FROM users WHERE id > 1 AND unknown = 2;"), 2, "", "Unknown column");
        expectSameBytes(changed, table);

        expectSuccess(run(workspace, "INSERT INTO users (name, email) VALUES ('new', 'd@x');"), "INSERT 1 (id=4)\n");
        expectSuccess(run(workspace, "SELECT id FROM users WHERE id >= 2 AND id != 3;"), "id\n2\n4\n");
        expectSuccess(run(workspace, "SELECT id FROM users WHERE id > 1 AND id <= 2;"), "id\n2\n");
    }

    private static Run run(Path workspace, String sql, String... options) throws IOException, InterruptedException {
        Path sqlFile = Files.writeString(workspace.resolve("query.sql"), sql);
        Path stdout = workspace.resolve("stdout.txt");
        Path stderr = workspace.resolve("stderr.txt");
        // 자식 프로세스는 임시 디렉터리에서 실행하므로 클래스 경로를 미리 절대 경로로 바꾼다.
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "minidbms.cli.Main"));
        command.addAll(List.of(options));
        command.add(sqlFile.toString());
        Process process = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("CLI timed out for: " + sql);
        }
        return new Run(process.exitValue(), Files.readString(stdout).replace("\r\n", "\n"), Files.readString(stderr));
    }

    private static void expectSuccess(Run run, String expectedOutput) {
        if (!run.equals(new Run(0, expectedOutput, ""))) {
            throw new AssertionError("Expected successful output:\n" + expectedOutput + "\nActual: " + run);
        }
    }

    private static void expectError(Run run, int expectedCode, String expectedOutput, String errorFragment) {
        if (run.code() != expectedCode || !run.stdout().equals(expectedOutput) || !run.stderr().contains(errorFragment)) {
            throw new AssertionError("Expected exit " + expectedCode + " and error containing "
                    + errorFragment + ", but got: " + run);
        }
    }

    private static void expectSameBytes(byte[] expected, Path table) throws IOException {
        if (!Arrays.equals(expected, Files.readAllBytes(table))) {
            throw new AssertionError("Unexpected changes to the table file");
        }
    }
}
