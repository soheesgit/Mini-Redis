package minidbms.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            printUsage(err);
            return 2;
        }
        if (args[0].equals("--help")) {
            printUsage(out);
            return 0;
        }

        String sql;
        try {
            sql = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException exception) {
            err.println("Cannot read SQL file: " + exception.getMessage());
            return 1;
        }

        if (sql.isBlank()) {
            err.println("SQL file is empty.");
            return 2;
        }

        err.println("SQL execution is pending Phase 1 implementation.");
        return 1;
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: java -cp out/main minidbms.cli.Main <sql-file>");
    }
}
