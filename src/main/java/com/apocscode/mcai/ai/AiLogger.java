package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive diagnostic logging system for MCAi.
 * Writes structured, timestamped entries to logs/mcai_debug.log
 * for behavior analysis, error tracking, and performance monitoring.
 *
 * Categories:
 *   CHAT         Player messages and AI responses
 *   TOOL_CALL    Tool invocations (name, args)
 *   TOOL_RESULT  Tool execution results (duration, result)
 *   AI_REQUEST   Outgoing Ollama requests
 *   AI_RESPONSE  Incoming Ollama responses
 *   COMMAND      Game command executions
 *   SECURITY     Security blocks and warnings
 *   CONFIG       Configuration changes / loads
 *   PERFORMANCE  Timing data
 *   ERROR        Errors and exceptions
 *   SYSTEM       Startup, shutdown, init
 *
 * Log format: [timestamp] [LEVEL] [CATEGORY] message
 * File rotates at 10MB (keeps one .old backup).
 */
public class AiLogger {
    private static final String LOG_FILE = "logs/mcai_debug.log";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // ---- Categories ----
    public enum Category {
        CHAT, TOOL_CALL, TOOL_RESULT, AI_REQUEST, AI_RESPONSE,
        COMMAND, SECURITY, CONFIG, PERFORMANCE, ERROR, SYSTEM
    }

    // ---- Session Statistics ----
    private static final AtomicInteger messageCount = new AtomicInteger(0);
    private static final AtomicInteger toolCallCount = new AtomicInteger(0);
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    private static final AtomicLong totalAiResponseTimeMs = new AtomicLong(0);
    private static final AtomicInteger aiResponseCount = new AtomicInteger(0);
    private static final AtomicLong totalToolTimeMs = new AtomicLong(0);
    private static final AtomicInteger commandCount = new AtomicInteger(0);
    private static final AtomicInteger blockedCommandCount = new AtomicInteger(0);

    private static PrintWriter writer;
    private static boolean initialized = false;
    private static long sessionStartMs;

    // ================================================================
    // Lifecycle
    // ================================================================

    public static void init() {
        try {
            File logFile = new File(LOG_FILE);
            logFile.getParentFile().mkdirs();

            // Rotate if too large
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                File backup = new File(LOG_FILE + ".old");
                if (backup.exists()) backup.delete();
                logFile.renameTo(backup);
            }

            writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)),
                    true); // auto-flush
            initialized = true;
            sessionStartMs = System.currentTimeMillis();

            log(Category.SYSTEM, "INFO",
                    "============================================");
            log(Category.SYSTEM, "INFO",
                    "=== MCAi Debug Logger Started ===");
            log(Category.SYSTEM, "INFO",
                    "Log file: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            MCAi.LOGGER.error("Failed to initialize AiLogger: {}", e.getMessage());
        }
    }

    public static void shutdown() {
        if (initialized) {
            log(Category.SYSTEM, "INFO", getSessionStats());
            log(Category.SYSTEM, "INFO",
                    "=== MCAi Debug Logger Shutdown ===");
            log(Category.SYSTEM, "INFO",
                    "============================================");
            synchronized (AiLogger.class) {
                if (writer != null) {
                    writer.close();
                    writer = null;
                }
            }
            initialized = false;
        }
    }

    // ================================================================
    // Core log method
    // ================================================================

    public static void log(Category category, String level, String message) {
        if (!initialized) return;

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String entry = String.format("[%s] [%-5s] [%-12s] %s",
                timestamp, level, category, message);

        synchronized (AiLogger.class) {
            if (writer != null) {
                writer.println(entry);
            }
        }

        // Mirror to standard logger at appropriate level
        if ("ERROR".equals(level)) {
            MCAi.LOGGER.error("[{}] {}", category, message);
        } else if ("WARN".equals(level)) {
            MCAi.LOGGER.warn("[{}] {}", category, message);
        }
        // DEBUG/INFO go only to file to avoid console spam
    }

    // ================================================================
    // Convenience methods — Chat
    // ================================================================

    /** Log an incoming player message */
    public static void chat(String playerName, String message) {
        if (!isEnabled()) return;
        messageCount.incrementAndGet();
        log(Category.CHAT, "INFO",
                String.format("Player '%s' >>> %s", playerName, message));
    }

    /** Log an outgoing AI response */
    public static void aiResponse(String response, long durationMs) {
        if (!isEnabled()) return;
        aiResponseCount.incrementAndGet();
        totalAiResponseTimeMs.addAndGet(durationMs);
        if (isLogAiRequests()) {
            log(Category.AI_RESPONSE, "INFO",
                    String.format("[%dms] Response (%d chars): %.200s%s",
                            durationMs, response.length(), response,
                            response.length() > 200 ? "..." : ""));
        }
    }

    // ================================================================
    // Convenience methods — Tool calls
    // ================================================================

    /** Log a tool invocation (before execution) */
    public static void toolCall(String toolName, String argsSummary) {
        if (!isEnabled()) return;
        toolCallCount.incrementAndGet();
        if (isLogToolCalls()) {
            log(Category.TOOL_CALL, "INFO",
                    String.format(">>> CALL tool '%s' args: %s", toolName, truncate(argsSummary, 500)));
        }
    }

    /** Log a tool result (after execution) */
    public static void toolResult(String toolName, String result, long durationMs) {
        if (!isEnabled()) return;
        totalToolTimeMs.addAndGet(durationMs);
        if (isLogToolCalls()) {
            log(Category.TOOL_RESULT, "INFO",
                    String.format("<<< RESULT tool '%s' [%dms] (%d chars): %.300s%s",
                            toolName, durationMs, result.length(), result,
                            result.length() > 300 ? "..." : ""));
        }
        if (isLogPerformance()) {
            log(Category.PERFORMANCE, "INFO",
                    String.format("Tool '%s': %dms", toolName, durationMs));
        }
    }

    /** Log a tool execution error */
    public static void toolError(String toolName, String error, Throwable exception) {
        errorCount.incrementAndGet();
        log(Category.ERROR, "ERROR",
                String.format("Tool '%s' FAILED: %s", toolName, error));
        if (exception != null) {
            logStackTrace(exception);
        }
    }

    /** Log when a tool is blocked by ability config */
    public static void toolDisabled(String toolName) {
        log(Category.SECURITY, "WARN",
                String.format("Tool '%s' blocked — disabled in config", toolName));
    }

    // ================================================================
    // Convenience methods — AI requests
    // ================================================================

    /** Log an outgoing Ollama request */
    public static void aiRequest(int messageCount, int toolCount, String model) {
        if (!isEnabled() || !isLogAiRequests()) return;
        log(Category.AI_REQUEST, "INFO",
                String.format("Sending to Ollama (model=%s, messages=%d, tools=%d)",
                        model, messageCount, toolCount));
    }

    /** Log agent loop iteration */
    public static void agentIteration(int iteration, int toolCallsThisRound) {
        if (!isEnabled() || !isLogAiRequests()) return;
        log(Category.AI_REQUEST, "INFO",
                String.format("Agent loop iteration %d — %d tool call(s) requested",
                        iteration + 1, toolCallsThisRound));
    }

    // ================================================================
    // Convenience methods — Commands
    // ================================================================

    /** Log a game command execution */
    public static void command(String command, boolean success, String result) {
        if (!isEnabled()) return;
        commandCount.incrementAndGet();
        log(Category.COMMAND, success ? "INFO" : "WARN",
                String.format("/%s %s: %s",
                        command, success ? "OK" : "FAIL", truncate(result, 200)));
    }

    /** Log a blocked command attempt */
    public static void commandBlocked(String command, String reason) {
        blockedCommandCount.incrementAndGet();
        log(Category.SECURITY, "WARN",
                String.format("BLOCKED command '/%s': %s", command, reason));
    }

    // ================================================================
    // Convenience methods — Security & Config
    // ================================================================

    /** Log a security event */
    public static void security(String action, String detail) {
        log(Category.SECURITY, "WARN",
                String.format("%s: %s", action, detail));
    }

    /** Log a config load/change event */
    public static void config(String key, String value) {
        log(Category.CONFIG, "INFO",
                String.format("Config %s = %s", key, value));
    }

    // ================================================================
    // Convenience methods — Performance
    // ================================================================

    /** Log a generic performance measurement */
    public static void performance(String operation, long durationMs) {
        if (!isEnabled() || !isLogPerformance()) return;
        log(Category.PERFORMANCE, "INFO",
                String.format("%s: %dms", operation, durationMs));
    }

    // ================================================================
    // Convenience methods — Errors
    // ================================================================

    /** Log an error with optional exception */
    public static void error(String context, Throwable exception) {
        errorCount.incrementAndGet();
        log(Category.ERROR, "ERROR",
                String.format("%s: %s", context,
                        exception != null ? exception.getMessage() : "unknown"));
        if (exception != null) {
            logStackTrace(exception);
        }
    }

    /** Log an error message without exception */
    public static void error(String message) {
        errorCount.incrementAndGet();
        log(Category.ERROR, "ERROR", message);
    }

    // ================================================================
    // Session Statistics
    // ================================================================

    /** Get session stats as a formatted string */
    public static String getSessionStats() {
        long uptime = (System.currentTimeMillis() - sessionStartMs) / 1000;
        long avgAiMs = aiResponseCount.get() > 0
                ? totalAiResponseTimeMs.get() / aiResponseCount.get() : 0;
        long avgToolMs = toolCallCount.get() > 0
                ? totalToolTimeMs.get() / toolCallCount.get() : 0;

        return String.format(
                "SESSION STATS: uptime=%ds, messages=%d, aiResponses=%d (avg %dms), " +
                "toolCalls=%d (avg %dms), commands=%d, blocked=%d, errors=%d",
                uptime, messageCount.get(), aiResponseCount.get(), avgAiMs,
                toolCallCount.get(), avgToolMs, commandCount.get(),
                blockedCommandCount.get(), errorCount.get());
    }

    /** Reset session statistics (for testing) */
    public static void resetStats() {
        messageCount.set(0);
        toolCallCount.set(0);
        errorCount.set(0);
        totalAiResponseTimeMs.set(0);
        aiResponseCount.set(0);
        totalToolTimeMs.set(0);
        commandCount.set(0);
        blockedCommandCount.set(0);
        sessionStartMs = System.currentTimeMillis();
    }

    // ================================================================
    // Internal helpers
    // ================================================================

    private static boolean isEnabled() {
        try {
            return AiConfig.DEBUG_LOGGING.get();
        } catch (Exception e) {
            return true; // Default enabled if config not loaded yet
        }
    }

    private static boolean isLogToolCalls() {
        try { return AiConfig.LOG_TOOL_CALLS.get(); }
        catch (Exception e) { return true; }
    }

    private static boolean isLogAiRequests() {
        try { return AiConfig.LOG_AI_REQUESTS.get(); }
        catch (Exception e) { return true; }
    }

    private static boolean isLogPerformance() {
        try { return AiConfig.LOG_PERFORMANCE.get(); }
        catch (Exception e) { return true; }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static void logStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        // Write each line individually for readability
        for (String line : sw.toString().split("\n")) {
            log(Category.ERROR, "ERROR", "  " + line.trim());
        }
    }
}
