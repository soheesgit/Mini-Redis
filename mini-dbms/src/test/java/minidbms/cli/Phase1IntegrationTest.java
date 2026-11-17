package minidbms.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Phase1IntegrationTest {
    private record Run(int code, String stdout, String stderr) {}

    private Phase1IntegrationTest() {}

    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("mini-dbms-phase1-");
        try {
            verifiesPersistence(workspace);
            rejectsInvalidQueries(workspace);
            reportsCorruptRecords(workspace);
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        System.out.println("Passed 3 Phase 1 integration scenarios.");
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
        Files.write(table, new byte[] {0}, StandardOpenOption.APPEND);
        byte[] corrupt = Files.readAllBytes(table);

        expectError(run(workspace, "SELECT * FROM users;"), 1, "",
                "at byte " + validSize + ": incomplete record length");
        expectError(run(workspace, "INSERT INTO users (name, email) VALUES ('x', 'x');"), 1, "",
                "at byte " + validSize + ": incomplete record length");
        expectSameBytes(corrupt, table);

        Files.write(table, Arrays.copyOf(corrupt, (int) validSize));
        Files.write(table, new byte[] {0, 0, 0, 16, 0}, StandardOpenOption.APPEND);
        expectError(run(workspace, "SELECT * FROM users;"), 1, "",
                "at byte " + validSize + ": incomplete record");
    }

    private static Run run(Path workspace, String sql, String... options) throws IOException, InterruptedException {
        Path sqlFile = Files.writeString(workspace.resolve("query.sql"), sql);
        Path stdout = workspace.resolve("stdout.txt");
        Path stderr = workspace.resolve("stderr.txt");
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
