package minidbms.cli;

import minidbms.engine.SqlEngine;
import minidbms.sql.SqlException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.StringJoiner;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

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

        try {
            Path dataDirectory = Path.of(customDirectory ? args[1] : "data");
            new SqlEngine(dataDirectory).execute(sql, result -> printResult(result, out));
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
