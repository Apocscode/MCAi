package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web search tool using DuckDuckGo HTML.
 * No API key required — scrapes the lite/html version.
 */
public class WebSearchTool implements AiTool {
    private static final String SEARCH_URL = "https://html.duckduckgo.com/html/?q=";
    private static final int MAX_RESULTS = 5;
    private static final int TIMEOUT_MS = 10000;

    // Pattern to extract search result titles and snippets from DDG HTML
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>.*?<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern URL_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"");
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the internet for information. Use this when you need to look up " +
                "Minecraft mod details, crafting recipes, build guides, game mechanics, " +
                "or any information you're not confident about. Returns top search results " +
                "with titles, URLs, and snippets.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "The search query. Be specific — include mod names, " +
                "Minecraft version, and key terms. Example: 'Mekanism fusion reactor setup guide 1.21'");
        props.add("query", query);
        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("query");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        String query = args.has("query") ? args.get("query").getAsString() : "";
        if (query.isBlank()) return "Error: empty search query";

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = SEARCH_URL + encoded;

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MCAi/1.0)");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code != 200) {
                return "Search failed with HTTP " + code;
            }

            StringBuilder html = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line).append("\n");
                }
            }
            conn.disconnect();

            return parseResults(html.toString(), query);

        } catch (Exception e) {
            MCAi.LOGGER.error("Web search failed for '{}': {}", query, e.getMessage());
            return "Search failed: " + e.getMessage();
        }
    }

    private String parseResults(String html, String query) {
        List<SearchResult> results = new ArrayList<>();

        // Extract individual result blocks
        String[] blocks = html.split("class=\"result results_links");
        for (int i = 1; i < blocks.length && results.size() < MAX_RESULTS; i++) {
            String block = blocks[i];

            String title = extractFirst(TITLE_PATTERN, block);
            String snippet = extractFirst(SNIPPET_PATTERN, block);
            String url = extractFirst(URL_PATTERN, block);

            if (title != null && !title.isBlank()) {
                title = stripHtml(title).trim();
                snippet = snippet != null ? stripHtml(snippet).trim() : "";
                url = url != null ? cleanUrl(url) : "";

                results.add(new SearchResult(title, url, snippet));
            }
        }

        if (results.isEmpty()) {
            return "No results found for: " + query;
        }

        // Format results for the AI
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: ").append(query).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title).append("\n");
            if (!r.url.isEmpty()) sb.append("   URL: ").append(r.url).append("\n");
            if (!r.snippet.isEmpty()) sb.append("   ").append(r.snippet).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ");
    }

    private String cleanUrl(String url) {
        // DDG wraps URLs in a redirect — extract the actual URL
        if (url.contains("uddg=")) {
            try {
                String decoded = java.net.URLDecoder.decode(
                        url.substring(url.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                int ampIdx = decoded.indexOf('&');
                return ampIdx > 0 ? decoded.substring(0, ampIdx) : decoded;
            } catch (Exception e) {
                return url;
            }
        }
        return url;
    }

    private record SearchResult(String title, String url, String snippet) {}
}
