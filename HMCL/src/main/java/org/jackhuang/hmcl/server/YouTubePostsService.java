/*
 * BarrilMC Launcher
 * Copyright (C) 2026 BarrilMC contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.io.HttpRequest;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/// Fetches community posts from a YouTube channel's /posts page by scraping
/// the embedded ytInitialData JSON blob.
///
/// YouTube does not expose an RSS feed or public API for community posts, so
/// we parse the page HTML. The parsing is tolerant of structure changes: it
/// does a breadth-first traversal of the JSON tree looking for any object that
/// contains "backstagePostRenderer", so minor layout rearrangements will not
/// break it.
public final class YouTubePostsService {

    private YouTubePostsService() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static final class PostEntry {
        private final String postId;
        private final String text;
        private final String publishedTime;

        public PostEntry(String postId, String text, String publishedTime) {
            this.postId = postId;
            this.text = text;
            this.publishedTime = publishedTime;
        }

        public String getPostId() {
            return postId;
        }

        public String getText() {
            return text;
        }

        public String getPublishedTime() {
            return publishedTime;
        }

        /// Full URL to the post on YouTube.
        public String getUrl() {
            return "https://www.youtube.com/post/" + postId;
        }

        /// Text truncated to ≤ 160 chars for display in a card.
        public String getSnippet() {
            String trimmed = text.trim();
            if (trimmed.length() <= 160) return trimmed;
            return trimmed.substring(0, 157) + "...";
        }
    }

    /// Fetches community posts for the given channel handle (e.g. "@barrilmc").
    public static List<PostEntry> fetchPosts(String handle) throws IOException {
        String url = "https://www.youtube.com/" + handle + "/posts";
        String html = HttpRequest.GET(url)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                // SOCS cookie bypasses YouTube's consent/cookie banner in most regions.
                .header("Cookie", "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.en+FX+")
                .getString();

        return parsePosts(html);
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static List<PostEntry> parsePosts(String html) throws IOException {
        String json = extractYtInitialData(html);
        if (json == null) {
            throw new IOException("ytInitialData not found in YouTube posts page "
                    + "(consent page may have been served)");
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            throw new IOException("Failed to parse ytInitialData JSON", e);
        }

        List<PostEntry> posts = new ArrayList<>();
        findBackstagePosts(root, posts);
        return posts;
    }

    /// Extracts the ytInitialData JSON string from the raw page HTML.
    /// YouTube embeds it as: var ytInitialData = {...};</script>
    private static String extractYtInitialData(String html) {
        String[] markers = {"var ytInitialData = ", "ytInitialData = "};
        for (String marker : markers) {
            int idx = html.indexOf(marker);
            if (idx == -1) continue;

            int jsonStart = html.indexOf('{', idx + marker.length());
            if (jsonStart == -1) continue;

            // The JSON ends at the </script> tag that closes this inline script.
            int scriptEnd = html.indexOf("</script>", jsonStart);
            String candidate = scriptEnd > jsonStart
                    ? html.substring(jsonStart, scriptEnd).trim()
                    : html.substring(jsonStart).trim();

            // Strip trailing semicolon (var ytInitialData = {...};)
            if (candidate.endsWith(";")) {
                candidate = candidate.substring(0, candidate.length() - 1).trim();
            }
            return candidate;
        }
        return null;
    }

    /// Iterative DFS over the JSON tree looking for any JsonObject that has a
    /// "backstagePostRenderer" key. When found, parses the post and stops
    /// recursing into that node (avoids duplicates from nested renderers).
    private static void findBackstagePosts(JsonElement root, List<PostEntry> result) {
        Deque<JsonElement> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            JsonElement element = stack.pop();

            if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
                continue;
            }

            if (element.isJsonArray()) {
                JsonArray arr = element.getAsJsonArray();
                // Push in reverse so first element is processed first
                for (int i = arr.size() - 1; i >= 0; i--) {
                    stack.push(arr.get(i));
                }
                continue;
            }

            JsonObject obj = element.getAsJsonObject();
            if (obj.has("backstagePostRenderer")) {
                try {
                    PostEntry entry = parsePostRenderer(
                            obj.getAsJsonObject("backstagePostRenderer"));
                    if (entry != null) result.add(entry);
                } catch (Exception ignored) {
                    // Skip malformed post entries
                }
                // Do NOT recurse into this renderer to avoid inner duplicates.
                continue;
            }

            // Recurse into all child values
            for (Map.Entry<String, JsonElement> field : obj.entrySet()) {
                stack.push(field.getValue());
            }
        }
    }

    private static PostEntry parsePostRenderer(JsonObject renderer) {
        // postId is required
        if (!renderer.has("postId")) return null;
        String postId = renderer.get("postId").getAsString();

        // contentText.runs[].text — concatenate all text runs
        StringBuilder text = new StringBuilder();
        if (renderer.has("contentText")) {
            JsonObject contentText = renderer.getAsJsonObject("contentText");
            if (contentText.has("runs")) {
                for (JsonElement run : contentText.getAsJsonArray("runs")) {
                    if (run.isJsonObject() && run.getAsJsonObject().has("text")) {
                        text.append(run.getAsJsonObject().get("text").getAsString());
                    }
                }
            }
        }

        // publishedTimeText.simpleText — relative time like "hace 2 días"
        String time = "";
        if (renderer.has("publishedTimeText")) {
            JsonObject timeObj = renderer.getAsJsonObject("publishedTimeText");
            if (timeObj.has("simpleText")) {
                time = timeObj.get("simpleText").getAsString();
            }
        }

        String postText = text.toString().trim();
        if (postText.isEmpty()) return null; // Skip image-only or empty posts

        return new PostEntry(postId, postText, time);
    }
}
