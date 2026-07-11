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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Backend access for the global launcher chat, stored under {@code /chat} in Firebase. Each message
/// is a push-id keyed object {@code {name, text, ts}}; push-ids sort chronologically so we order by
/// {@code "$key"}.
@NotNullByDefault
public final class BarrilmcChatService {

    /// How many recent messages to keep on screen.
    public static final int HISTORY_LIMIT = 120;
    /// Hard cap on a single message so nobody floods the shared node.
    public static final int MAX_MESSAGE_LENGTH = 280;

    private BarrilmcChatService() {
    }

    /// A single chat line. {@code id} is the Firebase push key (also the chronological sort key).
    public static final class ChatMessage {
        private final String id;
        private final String name;
        private final String text;
        private final long timestamp;

        public ChatMessage(String id, String name, String text, long timestamp) {
            this.id = id;
            this.name = name;
            this.text = text;
            this.timestamp = timestamp;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getText() {
            return text;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /// Fetches the most recent messages, oldest first. Returns an empty list on any error.
    public static List<ChatMessage> fetchRecent() {
        String query = "orderBy=" + BarrilmcCloud.encode("\"$key\"") + "&limitToLast=" + HISTORY_LIMIT;
        JsonElement element = BarrilmcCloud.get("chat", query);
        List<ChatMessage> messages = new ArrayList<>();
        if (element == null || !element.isJsonObject()) {
            return messages;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject obj = value.getAsJsonObject();
            String name = string(obj.get("name"), "???");
            String text = string(obj.get("text"), "");
            long ts = obj.has("ts") && obj.get("ts").isJsonPrimitive() ? obj.get("ts").getAsLong() : 0L;
            if (!text.isBlank()) {
                messages.add(new ChatMessage(entry.getKey(), name, text, ts));
            }
        }
        // Push-ids are lexicographically chronological.
        messages.sort((a, b) -> a.id.compareTo(b.id));
        return messages;
    }

    /// Posts a message authored by {@code name}. Returns true on success.
    public static boolean send(String name, String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            trimmed = trimmed.substring(0, MAX_MESSAGE_LENGTH);
        }
        JsonObject message = new JsonObject();
        message.add("name", new JsonPrimitive(name));
        message.add("text", new JsonPrimitive(trimmed));
        // {".sv":"timestamp"} -> Firebase stamps the server time so clocks stay consistent.
        JsonObject sv = new JsonObject();
        sv.add(".sv", new JsonPrimitive("timestamp"));
        message.add("ts", sv);
        return BarrilmcCloud.post("chat", message.toString()) != null;
    }

    private static String string(@Nullable JsonElement element, String fallback) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }
}
