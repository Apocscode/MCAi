package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side voice input service using local Whisper (speech-to-text).
 *
 * Records audio from the microphone, sends it to a local Whisper-compatible
 * HTTP endpoint (faster-whisper-server, whisper.cpp, etc.), and returns
 * the transcribed text.
 *
 * Default endpoint: http://localhost:8178/v1/audio/transcriptions
 * (OpenAI-compatible format used by faster-whisper-server)
 *
 * Setup: Install faster-whisper-server or whisper.cpp server locally.
 * Example: pip install faster-whisper-server && faster-whisper-server
 */
public class WhisperService {

    private static final String WHISPER_MODEL = "base"; // Model hint for the API
    private static final int TIMEOUT_MS = 15000;

    /**
     * Get the Whisper endpoint URL from config, forcing IPv4 to avoid Java's
     * localhost → IPv6 resolution which fails when Whisper binds IPv4 only.
     */
    private static String getWhisperUrl() {
        try {
            return AiConfig.WHISPER_URL.get().replace("localhost", "127.0.0.1");
        } catch (Exception e) {
            return "http://127.0.0.1:8178/v1/audio/transcriptions";
        }
    }

    // Audio format: 16kHz mono 16-bit PCM — what Whisper expects
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(
            16000.0f,  // Sample rate
            16,        // Sample size in bits
            1,         // Channels (mono)
            true,      // Signed
            false      // Little-endian
    );

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MCAi-Whisper");
        t.setDaemon(true);
        return t;
    });

    private static TargetDataLine micLine;
    private static ByteArrayOutputStream audioBuffer;
    private static final AtomicBoolean isRecording = new AtomicBoolean(false);
    private static volatile boolean available = false;

    /**
     * Initialize the whisper service — check if mic is available.
     */
    public static void init() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                MCAi.LOGGER.warn("Whisper: No microphone available (line not supported)");
                available = false;
                return;
            }
            available = true;
            MCAi.LOGGER.info("Whisper voice input ready (endpoint: {})", getWhisperUrl());
        } catch (Exception e) {
            MCAi.LOGGER.warn("Whisper: Failed to check microphone: {}", e.getMessage());
            available = false;
        }
    }

    /**
     * @return true if microphone hardware is available
     */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * @return true if currently recording audio
     */
    public static boolean isRecording() {
        return isRecording.get();
    }

    /**
     * Start recording audio from the microphone.
     * Call stopRecordingAndTranscribe() to stop and get text.
     */
    public static void startRecording() {
        if (!available || isRecording.get()) return;

        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(AUDIO_FORMAT);
            micLine.start();

            audioBuffer = new ByteArrayOutputStream();
            isRecording.set(true);

            // Background thread reads mic data continuously
            executor.submit(() -> {
                byte[] buffer = new byte[4096];
                while (isRecording.get() && micLine != null && micLine.isOpen()) {
                    int bytesRead = micLine.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        audioBuffer.write(buffer, 0, bytesRead);
                    }
                }
            });

            MCAi.LOGGER.info("Whisper: Recording started");
        } catch (LineUnavailableException e) {
            MCAi.LOGGER.error("Whisper: Failed to open microphone: {}", e.getMessage());
            isRecording.set(false);
        }
    }

    /**
     * Stop recording and send audio to Whisper for transcription.
     *
     * @return Future with the transcribed text, or error message
     */
    public static CompletableFuture<String> stopRecordingAndTranscribe() {
        if (!isRecording.get()) {
            return CompletableFuture.completedFuture("");
        }

        isRecording.set(false);

        // Stop and close mic
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }

        byte[] rawAudio = audioBuffer.toByteArray();
        audioBuffer = null;

        if (rawAudio.length < 3200) { // Less than 0.1 seconds of audio
            MCAi.LOGGER.info("Whisper: Recording too short, ignoring");
            return CompletableFuture.completedFuture("");
        }

        MCAi.LOGGER.info("Whisper: Recorded {} bytes ({} seconds), sending to Whisper...",
                rawAudio.length, String.format("%.1f", rawAudio.length / 32000.0));

        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] wavData = rawPcmToWav(rawAudio);
                return callWhisperApi(wavData);
            } catch (Exception e) {
                MCAi.LOGGER.error("Whisper transcription failed: {}", e.getMessage());
                return "[Voice error: " + e.getMessage() + "]";
            }
        }, executor);
    }

    /**
     * Cancel recording without transcribing.
     */
    public static void cancelRecording() {
        if (!isRecording.get()) return;
        isRecording.set(false);
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        audioBuffer = null;
        MCAi.LOGGER.info("Whisper: Recording cancelled");
    }

    /**
     * Convert raw PCM bytes to a WAV file (adds the 44-byte header).
     */
    private static byte[] rawPcmToWav(byte[] pcmData) {
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataSize);
        try {
            // RIFF header
            wav.write("RIFF".getBytes());
            wav.write(intToLittleEndian(fileSize));
            wav.write("WAVE".getBytes());

            // fmt chunk
            wav.write("fmt ".getBytes());
            wav.write(intToLittleEndian(16));       // Chunk size
            wav.write(shortToLittleEndian((short) 1)); // PCM format
            wav.write(shortToLittleEndian((short) 1)); // Mono
            wav.write(intToLittleEndian(16000));    // Sample rate
            wav.write(intToLittleEndian(32000));    // Byte rate (16000 * 2)
            wav.write(shortToLittleEndian((short) 2)); // Block align
            wav.write(shortToLittleEndian((short) 16)); // Bits per sample

            // data chunk
            wav.write("data".getBytes());
            wav.write(intToLittleEndian(dataSize));
            wav.write(pcmData);
        } catch (IOException ignored) {
        }

        return wav.toByteArray();
    }

    /**
     * Send WAV audio to the Whisper API endpoint (OpenAI-compatible).
     * Uses multipart/form-data upload.
     */
    private static String callWhisperApi(byte[] wavData) throws IOException {
        String boundary = "----MCAiWhisper" + System.currentTimeMillis();

        HttpURLConnection conn = (HttpURLConnection) URI.create(getWhisperUrl()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try (OutputStream os = conn.getOutputStream()) {
            // File field
            writeMultipartField(os, boundary, "file", "recording.wav", "audio/wav", wavData);

            // Model field
            writeMultipartString(os, boundary, "model", WHISPER_MODEL);

            // Language hint (optional, speed up transcription)
            writeMultipartString(os, boundary, "language", "en");

            // Response format
            writeMultipartString(os, boundary, "response_format", "json");

            // End boundary
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("Whisper API returned HTTP " + responseCode + ": " + error);
        }

        String responseBody = readStream(conn.getInputStream());
        conn.disconnect();

        // Parse JSON response — expected: {"text": "transcribed text"}
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("text")) {
                String text = json.get("text").getAsString().trim();
                MCAi.LOGGER.info("Whisper transcription: '{}'", text);
                return text;
            }
        } catch (Exception e) {
            MCAi.LOGGER.warn("Whisper: unexpected response format: {}", responseBody);
        }

        return responseBody.trim();
    }

    private static void writeMultipartField(OutputStream os, String boundary,
                                             String fieldName, String fileName,
                                             String contentType, byte[] data) throws IOException {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: " + contentType + "\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeMultipartString(OutputStream os, String boundary,
                                              String fieldName, String value) throws IOException {
        String part = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n" +
                value + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] intToLittleEndian(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private static byte[] shortToLittleEndian(short value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    public static void shutdown() {
        cancelRecording();
        executor.shutdown();
    }
}
