package top.diaoyugan.perPlayerLoot.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the SQLite connection, pragmas, transactions, and shutdown lifecycle. */
final class SqliteDatabase implements AutoCloseable {
    private final File file;
    private final Logger logger;
    private Connection connection;

    SqliteDatabase(final File file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    void open(final String password) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.file.getAbsolutePath());
        try (Statement statement = this.connection.createStatement()) {
            if (password != null && !password.isBlank()) {
                statement.execute("PRAGMA key = '" + password.replace("'", "''") + "'");
            }
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    Connection connection() {
        if (this.connection == null) throw new IllegalStateException("Loot storage is not loaded.");
        return this.connection;
    }

    <T> T transaction(final SqlTransaction<T> transaction) {
        Connection connection = connection();
        try {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = transaction.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("SQLite loot storage transaction failed.", exception);
        }
    }

    @Override public void close() {
        if (this.connection == null) return;
        try { this.connection.close(); }
        catch (SQLException exception) { this.logger.log(Level.SEVERE, "Could not close SQLite loot storage.", exception); }
        finally { this.connection = null; }
    }

    @FunctionalInterface
    interface SqlTransaction<T> { T execute(Connection connection) throws SQLException; }
}
