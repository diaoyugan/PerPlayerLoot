package top.diaoyugan.perPlayerLoot.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the SQLite connection and serializes every operation on one database thread. */
final class SqliteDatabase implements AutoCloseable {
    private final File file;
    private final Logger logger;
    private final ExecutorService executor;
    private volatile Thread databaseThread;
    private volatile Connection connection;

    SqliteDatabase(final File file, final Logger logger) {
        this.file = file;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "PerPlayerLoot-SQLite");
            thread.setDaemon(true);
            this.databaseThread = thread;
            return thread;
        });
    }

    void open(final String password) throws SQLException {
        if (this.connection != null) throw new SQLException("SQLite loot storage is already open.");
        try {
            this.executor.submit(() -> {
                Connection opened = DriverManager.getConnection("jdbc:sqlite:" + this.file.getAbsolutePath());
                try {
                    configure(opened, password);
                    this.connection = opened;
                } catch (SQLException exception) {
                    try {
                        opened.close();
                    } catch (SQLException closeException) {
                        exception.addSuppressed(closeException);
                    }
                    throw exception;
                }
                return null;
            }).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while opening SQLite storage.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SQLException sqlException) throw sqlException;
            throw new SQLException("Could not open SQLite storage.", cause);
        }
    }

    private static void configure(final Connection connection, final String password) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (password != null && !password.isBlank()) {
                statement.execute("PRAGMA key = '" + password.replace("'", "''") + "'");
            }
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    Connection connection() {
        if (Thread.currentThread() != this.databaseThread) {
            throw new IllegalStateException("SQLite connection accessed outside the database executor.");
        }
        if (this.connection == null) throw new IllegalStateException("Loot storage is not loaded.");
        return this.connection;
    }

    <T> T execute(final SqlOperation<T> operation) {
        if (Thread.currentThread() == this.databaseThread) {
            try {
                return operation.execute(connection());
            } catch (SQLException exception) {
                throw new IllegalStateException("SQLite loot storage operation failed.", exception);
            }
        }
        try {
            return submit(operation).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SQLException sqlException) {
                throw new IllegalStateException("SQLite loot storage operation failed.", sqlException);
            }
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("SQLite loot storage operation failed.", cause);
        }
    }

    <T> CompletableFuture<T> submit(final SqlOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.execute(connection());
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, this.executor);
    }

    <T> T transaction(final SqlTransaction<T> transaction) {
        return execute(connection -> runTransaction(connection, transaction));
    }

    <T> CompletableFuture<T> submitTransaction(final SqlTransaction<T> transaction) {
        return submit(connection -> runTransaction(connection, transaction));
    }

    private <T> T runTransaction(
        final Connection connection,
        final SqlTransaction<T> transaction
    ) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        T result;
        try {
            result = transaction.execute(connection);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreException) {
                exception.addSuppressed(restoreException);
            }
            throw exception;
        }

        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException exception) {
            // COMMIT already succeeded. Report the connection-state problem without making
            // callers believe the durable transaction itself failed.
            this.logger.log(Level.SEVERE, "Could not restore SQLite auto-commit state after commit.", exception);
        }
        return result;
    }

    @Override public void close() {
        if (this.executor.isShutdown()) return;
        try {
            if (this.connection != null) {
                execute(connection -> {
                    connection.close();
                    this.connection = null;
                    return null;
                });
            }
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not close SQLite loot storage.", exception);
        } finally {
            this.executor.shutdown();
        }
    }

    @FunctionalInterface
    interface SqlOperation<T> { T execute(Connection connection) throws SQLException; }

    @FunctionalInterface
    interface SqlTransaction<T> { T execute(Connection connection) throws SQLException; }
}
