package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manages the local Ollama instance:
 * - Detects if Ollama is installed
 * - Checks the installed version
 * - Starts Ollama serve if not already running
 * - Health-checks the API endpoint
 *
 * Called during mod initialization so Ollama is ready as a fallback
 * when Groq rate-limits or is unavailable.
 */
public class OllamaManager {

    private static final String OLLAMA_API = "http://127.0.0.1:11434";
    private static String ollamaPath = null;
    private static String ollamaVersion = null;
    private static boolean running = false;

    // Common install locations on Windows
    private static final List<String> SEARCH_PATHS = List.of(
            System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Ollama\\ollama.exe",
            "C:\\Program Files\\Ollama\\ollama.exe",
            "C:\\Program Files (x86)\\Ollama\\ollama.exe"
    );

    /**
     * Initialize Ollama: find installation, check version, start if needed.
     * Runs on a background thread to avoid blocking mod init.
     */
    public static void init() {
        Thread thread = new Thread(() -> {
            try {
                // Step 1: Check if already running
                if (isRunning()) {
                    MCAi.LOGGER.info("Ollama is already running at {}", OLLAMA_API);
                    running = true;
                    fetchVersion();
                    return;
                }

                // Step 2: Find the Ollama executable
                ollamaPath = findOllama();
                if (ollamaPath == null) {
                    MCAi.LOGGER.info("Ollama not found on this system — local fallback unavailable");
                    return;
                }
                MCAi.LOGGER.info("Found Ollama at: {}", ollamaPath);

                // Step 3: Check version
                ollamaVersion = getVersion(ollamaPath);
                if (ollamaVersion != null) {
                    MCAi.LOGGER.info("Ollama version: {}", ollamaVersion);
                } else {
                    MCAi.LOGGER.warn("Could not determine Ollama version");
                }

                // Step 4: Start Ollama serve
                MCAi.LOGGER.info("Starting Ollama serve...");
                startServe(ollamaPath);

                // Step 5: Wait for it to be ready (up to 30 seconds)
                boolean ready = waitForReady(30);
                if (ready) {
                    running = true;
                    MCAi.LOGGER.info("Ollama started successfully and is ready at {}", OLLAMA_API);
                } else {
                    MCAi.LOGGER.warn("Ollama started but did not become ready within 30 seconds");
                }

            } catch (Exception e) {
                MCAi.LOGGER.warn("Ollama auto-start failed: {}", e.getMessage());
            }
        }, "MCAi-OllamaInit");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Check if the Ollama API is reachable.
     */
    public static boolean isRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(OLLAMA_API).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Find the Ollama executable on the system.
     */
    private static String findOllama() {
        // Check known paths
        for (String path : SEARCH_PATHS) {
            if (Files.exists(Path.of(path))) {
                return path;
            }
        }

        // Try PATH via "where ollama"
        try {
            Process proc = new ProcessBuilder("where", "ollama")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank() && Files.exists(Path.of(line.trim()))) {
                    return line.trim();
                }
            }
            proc.waitFor();
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Get the Ollama version string.
     */
    private static String getVersion(String execPath) {
        try {
            Process proc = new ProcessBuilder(execPath, "--version")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = reader.readLine();
                proc.waitFor();
                return line != null ? line.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetch version from the running API endpoint.
     */
    private static void fetchVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(OLLAMA_API + "/api/version").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String body = reader.readLine();
                if (body != null) {
                    // Response is {"version":"0.15.6"}
                    ollamaVersion = body.replaceAll(".*\"version\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    MCAi.LOGGER.info("Ollama version (from API): {}", ollamaVersion);
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    /**
     * Start "ollama serve" as a background process.
     */
    private static void startServe(String execPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(execPath, "serve");
            pb.redirectErrorStream(true);
            // Don't inherit IO — let it run silently in background
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            // Don't hold a reference — it runs as an independent process
        } catch (Exception e) {
            MCAi.LOGGER.warn("Failed to start Ollama serve: {}", e.getMessage());
        }
    }

    /**
     * Wait for the Ollama API to become ready, polling every second.
     */
    private static boolean waitForReady(int maxSeconds) {
        for (int i = 0; i < maxSeconds; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isRunning()) {
                return true;
            }
        }
        return false;
    }

    // --- Accessors ---

    public static boolean isAvailable() {
        return running;
    }

    public static String getOllamaVersion() {
        return ollamaVersion;
    }

    public static String getOllamaPath() {
        return ollamaPath;
    }
}
