package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/** Executes generated DDL through JDBC when validation is explicitly enabled. */
public final class JdbcDdlValidationService {
    private final SqlScriptStatementParser parser;

    public JdbcDdlValidationService() {
        this(new SqlScriptStatementParser());
    }

    JdbcDdlValidationService(SqlScriptStatementParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
    }

    public DdlValidationResult validate(Path sourceDocument, Path sqlFile, DatabasePlatform platform,
                                        JdbcConnectionSettings settings) {
        Objects.requireNonNull(sourceDocument, "sourceDocument must not be null");
        Objects.requireNonNull(sqlFile, "sqlFile must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        int executed = 0;
        try {
            String script = Files.readString(sqlFile);
            List<String> statements = parser.parse(script, platform);
            try (Connection connection = DriverManager.getConnection(
                    settings.url(), settings.username(), settings.password());
                 Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                    executed++;
                }
            }
            return new DdlValidationResult(sourceDocument, sqlFile, platform,
                    DdlValidationStatus.PASSED, executed, "DDL executed successfully");
        } catch (Exception exception) {
            return new DdlValidationResult(sourceDocument, sqlFile, platform,
                    DdlValidationStatus.FAILED, executed, rootMessage(exception));
        }
    }

    public DdlValidationResult generatedOnly(Path sourceDocument, Path sqlFile, DatabasePlatform platform) {
        return new DdlValidationResult(sourceDocument, sqlFile, platform,
                DdlValidationStatus.GENERATED_ONLY, 0, "Execution validation not requested");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
