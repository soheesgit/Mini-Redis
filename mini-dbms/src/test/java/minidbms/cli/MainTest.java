package minidbms.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** CLI 인자와 입력 파일 오류를 종료 코드, 표준 출력, 표준 오류로 확인한다. */
public final class MainTest {
    private MainTest() {}

    public static void main(String[] args) throws IOException {
        check(new String[] {"--help"}, 0, "Usage:", "");
        check(new String[0], 2, "", "Usage:");
        check(new String[] {"one.sql", "two.sql"}, 2, "", "Usage:");

        Path sqlFile = Files.createTempFile("mini-dbms-", ".sql");
        try {
            Files.writeString(sqlFile, " \n\t");
            check(new String[] {sqlFile.toString()}, 2, "", "SQL file is empty.");

            check(new String[] {"--data-dir", sqlFile.toString()}, 2, "", "Usage:");
        } finally {
            Files.deleteIfExists(sqlFile);
        }

        check(new String[] {sqlFile.toString()}, 1, "", "Cannot read SQL file:");
        System.out.println("Passed 6 CLI checks.");
    }

    private static void check(String[] args, int expectedCode, String expectedOut, String expectedErr) {
        // 실제 터미널 대신 메모리 버퍼로 출력을 받아 stdout과 stderr를 따로 검사한다.
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode;
        try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(args, out, err);
        }

        String actualOut = stdout.toString(StandardCharsets.UTF_8);
        String actualErr = stderr.toString(StandardCharsets.UTF_8);
        if (exitCode != expectedCode || !matches(actualOut, expectedOut) || !matches(actualErr, expectedErr)) {
            throw new AssertionError("Unexpected CLI result\nexit code: " + exitCode
                    + "\nstdout: " + actualOut + "\nstderr: " + actualErr);
        }
    }

    private static boolean matches(String actual, String expected) {
        return expected.isEmpty() ? actual.isEmpty() : actual.startsWith(expected);
    }
}
