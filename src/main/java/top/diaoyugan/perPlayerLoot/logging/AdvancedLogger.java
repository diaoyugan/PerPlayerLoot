package top.diaoyugan.perPlayerLoot.logging;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Level;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;

/** Optional detailed audit logging to both the console and rotating plugin-owned files. */
public final class AdvancedLogger implements AutoCloseable {

    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024L;

    private final PerPlayerLoot plugin;
    private volatile boolean enabled;
    private volatile FileHandler fileHandler;
    private volatile ThreadPoolExecutor writer;
    private final AtomicBoolean queueWarningEmitted = new AtomicBoolean();

    public AdvancedLogger(final PerPlayerLoot plugin) {
        this.plugin = plugin;
    }

    public synchronized void reload() {
        stopWriter();
        closeHandler();
        this.enabled = this.plugin.settings().advancedLogging().enabled();
        if (!this.enabled) {
            return;
        }

        int maxFileSizeMb = this.plugin.settings().advancedLogging().maxFileSizeMb();
        int retainedFiles = this.plugin.settings().advancedLogging().retainedFiles();
        long requestedBytes = maxFileSizeMb * BYTES_PER_MEBIBYTE;
        int fileLimitBytes = (int) Math.min(Integer.MAX_VALUE, requestedBytes);
        File logFolder = new File(this.plugin.getDataFolder(), "logs");
        if (!logFolder.exists() && !logFolder.mkdirs()) {
            this.enabled = false;
            this.plugin.getLogger().warning("Could not create the advanced log directory: " + logFolder.getAbsolutePath());
            return;
        }

        try {
            String pattern = new File(logFolder, "advanced-%g.log").getAbsolutePath();
            this.fileHandler = new FileHandler(pattern, fileLimitBytes, retainedFiles, true);
            this.fileHandler.setEncoding(StandardCharsets.UTF_8.name());
            this.fileHandler.setLevel(Level.INFO);
            this.fileHandler.setFormatter(new AdvancedLogFormatter());
            this.writer = new ThreadPoolExecutor(
                1,
                1,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8192),
                runnable -> {
                    Thread thread = new Thread(runnable, "PerPlayerLoot-advanced-log");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> {
                    if (this.queueWarningEmitted.compareAndSet(false, true)) {
                        this.plugin.getLogger().warning(
                            "Advanced log queue is full; file entries will be dropped until the writer catches up."
                        );
                    }
                }
            );
            log(
                "Advanced logging enabled: directory=%s, maxFileSizeMb=%d, retainedFiles=%d",
                logFolder.getAbsolutePath(),
                maxFileSizeMb,
                retainedFiles
            );
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            this.enabled = false;
            closeHandler();
            this.plugin.getLogger().log(Level.WARNING, "Could not open the advanced log file.", exception);
        }
    }

    public void log(final String format, final Object... arguments) {
        if (!this.enabled) {
            return;
        }

        String message;
        try {
            message = String.format(Locale.ROOT, format, arguments);
        } catch (IllegalFormatException exception) {
            message = format + " [could not format advanced-log arguments: " + exception.getMessage() + "]";
        }
        message = message.replace('\r', ' ').replace('\n', ' ');
        this.plugin.getLogger().info("[Advanced] " + message);

        FileHandler handler = this.fileHandler;
        ThreadPoolExecutor currentWriter = this.writer;
        if (handler != null && currentWriter != null && !currentWriter.isShutdown()) {
            LogRecord record = new LogRecord(Level.INFO, message);
            record.setLoggerName(this.plugin.getName());
            currentWriter.execute(() -> handler.publish(record));
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    @Override
    public synchronized void close() {
        if (this.enabled) {
            log("Advanced logging stopped.");
        }
        this.enabled = false;
        stopWriter();
        closeHandler();
    }

    private void stopWriter() {
        ThreadPoolExecutor currentWriter = this.writer;
        this.writer = null;
        if (currentWriter == null) {
            return;
        }
        currentWriter.shutdown();
        try {
            if (!currentWriter.awaitTermination(5L, TimeUnit.SECONDS)) {
                currentWriter.shutdownNow();
            }
        } catch (InterruptedException exception) {
            currentWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
        this.queueWarningEmitted.set(false);
    }

    private void closeHandler() {
        if (this.fileHandler == null) {
            return;
        }
        this.fileHandler.flush();
        this.fileHandler.close();
        this.fileHandler = null;
    }

    private static final class AdvancedLogFormatter extends Formatter {

        private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

        @Override
        public String format(final LogRecord record) {
            return "[" + TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(record.getMillis())) + "]"
                + " [" + Thread.currentThread().getName() + "] "
                + record.getMessage()
                + System.lineSeparator();
        }
    }
}
