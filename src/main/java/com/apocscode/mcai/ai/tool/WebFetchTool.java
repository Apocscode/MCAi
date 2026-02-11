package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Fetches and extracts readable text from a webpage.
 * Strips HTML tags, scripts, styles, and returns clean text.
 * Useful for reading wiki pages, guides, documentation.
 */
public class WebFetchTool implements AiTool {
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_CONTENT_LENGTH = 8000; // Chars to return to AI

    @Override
    public String name() {
        return "fetch_webpage";
    }

    @Override
    public String description() {
        return "Fetch and read the text content of a specific webpage URL. " +
                "Use this after web_search to read a full wiki page, guide, or documentation. " +
                "Returns the main text content stripped of HTML.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject url = new JsonObject();
        url.addProperty("type", "string");
        url.addProperty("description", "The full URL of the webpage to fetch (e.g., https://minecraft.wiki/w/Iron_Ingot)");
        props.add("url", url);
        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("url");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        String url = args.has("url") ? args.get("url").getAsString() : "";
        if (url.isBlank()) return "Error: no URL provided";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MCAi/1.0)");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                return "Failed to fetch URL (HTTP " + code + "): " + url;
            }

            StringBuilder html = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                // Limit total read to prevent huge pages from consuming memory
                int totalRead = 0;
                while ((read = reader.read(buffer)) != -1 && totalRead < 200_000) {
                    html.append(buffer, 0, read);
                    totalRead += read;
                }
            }
            conn.disconnect();

            String text = extractReadableText(html.toString());

            // Truncate if too long
            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n[Content truncated — " +
                        text.length() + " chars total]";
            }

            return "Content from " + url + ":\n\n" + text;

        } catch (Exception e) {
            MCAi.LOGGER.error("Webpage fetch failed for '{}': {}", url, e.getMessage());
            return "Failed to fetch webpage: " + e.getMessage();
        }
    }

    /**
     * Extract readable text from HTML by removing scripts, styles, nav, and tags.
     */
    private String extractReadableText(String html) {
        // Remove script and style blocks entirely
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        text = text.replaceAll("(?is)<nav[^>]*>.*?</nav>", "");
        text = text.replaceAll("(?is)<header[^>]*>.*?</header>", "");
        text = text.replaceAll("(?is)<footer[^>]*>.*?</footer>", "");
        text = text.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "");
        text = text.replaceAll("(?is)<!--.*?-->", "");

        // Convert some tags to readable formatting
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p>", "\n\n");
        text = text.replaceAll("(?i)</div>", "\n");
        text = text.replaceAll("(?i)</h[1-6]>", "\n\n");
        text = text.replaceAll("(?i)<li[^>]*>", "- ");
        text = text.replaceAll("(?i)</li>", "\n");
        text = text.replaceAll("(?i)</tr>", "\n");
        text = text.replaceAll("(?i)</td>", " | ");

        // Strip all remaining HTML tags
        text = text.replaceAll("<[^>]+>", "");

        // Decode HTML entities
        text = text.replaceAll("&amp;", "&");
        text = text.replaceAll("&lt;", "<");
        text = text.replaceAll("&gt;", ">");
        text = text.replaceAll("&quot;", "\"");
        text = text.replaceAll("&#x27;", "'");
        text = text.replaceAll("&nbsp;", " ");
        text = text.replaceAll("&#\\d+;", "");
        text = text.replaceAll("&\\w+;", "");

        // Clean up whitespace
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\n[ \\t]+", "\n");
        text = text.replaceAll("\n{3,}", "\n\n");
        text = text.trim();

        return text;
    }
}
