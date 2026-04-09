package com.iispl.banksettlement.utility;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * PhaseLogger centralizes readable console output and per-phase log files.
 */
public final class PhaseLogger {

    private static final String LOG_DIR = "logs";
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final ThreadLocal<PhaseLogContext> CONTEXT = new ThreadLocal<>();

    private PhaseLogger() {
    }

    public static PhaseLogContext startPhase(String phaseCode, String phaseTitle) {
        closeCurrentPhase();

        String sanitizedCode = phaseCode.replaceAll("[^a-zA-Z0-9_-]", "_");
        String timestamp = LocalDateTime.now().format(TS_FORMAT);
        String loggerName = "Phase-" + sanitizedCode + "-" + timestamp;

        Logger logger = Logger.getLogger(loggerName);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        clearHandlers(logger);

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(new HumanReadableConsoleFormatter());
        logger.addHandler(consoleHandler);

        File logDir = new File(LOG_DIR);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        String logFilePath = LOG_DIR + File.separator + sanitizedCode + "_" + timestamp + ".log";
        try {
            FileHandler fileHandler = new FileHandler(logFilePath, true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new DetailedFileFormatter());
            logger.addHandler(fileHandler);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Unable to create phase log file: " + logFilePath, ex);
        }

        logger.info("==================================================");
        logger.info("PHASE START : " + phaseTitle);
        logger.info("LOG FILE    : " + logFilePath);
        logger.info("==================================================");

        PhaseLogContext ctx = new PhaseLogContext(phaseCode, phaseTitle, logger, logFilePath);
        CONTEXT.set(ctx);
        return ctx;
    }

    public static Logger getLogger() {
        PhaseLogContext ctx = CONTEXT.get();
        if (ctx != null) {
            return ctx.getLogger();
        }
        return Logger.getLogger("BankSettlement");
    }

    public static void configureReadableConsole(Logger logger) {
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        clearHandlers(logger);

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(new HumanReadableConsoleFormatter());
        logger.addHandler(consoleHandler);
    }

    public static void closeCurrentPhase() {
        PhaseLogContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.close();
            CONTEXT.remove();
        }
    }

    private static void clearHandlers(Logger logger) {
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
            handler.close();
        }
    }

    public static final class PhaseLogContext implements AutoCloseable {
        private final String phaseCode;
        private final String phaseTitle;
        private final Logger logger;
        private final String logFilePath;

        private PhaseLogContext(String phaseCode, String phaseTitle, Logger logger, String logFilePath) {
            this.phaseCode = phaseCode;
            this.phaseTitle = phaseTitle;
            this.logger = logger;
            this.logFilePath = logFilePath;
        }

        public Logger getLogger() {
            return logger;
        }

        public String getLogFilePath() {
            return logFilePath;
        }

        @Override
        public void close() {
            logger.info("PHASE COMPLETE: " + phaseTitle + " (" + phaseCode + ")");
            for (Handler handler : logger.getHandlers()) {
                handler.flush();
                handler.close();
                logger.removeHandler(handler);
            }
        }
    }

    private static final class HumanReadableConsoleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return String.format("[%s] %s%n", record.getLevel().getName(), record.getMessage());
        }
    }

    private static final class DetailedFileFormatter extends Formatter {
        private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String format(LogRecord record) {
            String when = LocalDateTime.now().format(FILE_TS);
            return String.format("%s | %-7s | %s%n", when, record.getLevel().getName(), record.getMessage());
        }
    }
}
