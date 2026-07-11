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
import org.jetbrains.annotations.NotNullByDefault;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/// Tracks which "regalos para todos" each player has already claimed locally, signed with
/// HMAC-SHA256 so a player can't delete entries to re-claim old gifts and farm extra tickets.
/// Same pattern as {@link CardInventoryStore} but only stores a set of gift ids.
@NotNullByDefault
public final class ClaimedGiftsStore {

    private static final String FILENAME = "claimed-gifts.json";
    private static final String ALGORITHM = "HmacSHA256";

    private ClaimedGiftsStore() {
    }

    public static synchronized Set<String> load(String playerName) {
        Path file = ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve(FILENAME);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashSet<>();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return new LinkedHashSet<>();
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("players") || !root.get("players").isJsonObject()) return new LinkedHashSet<>();
            JsonObject players = root.getAsJsonObject("players");
            if (!players.has(playerName) || !players.get(playerName).isJsonObject()) return new LinkedHashSet<>();

            JsonObject entry = players.getAsJsonObject(playerName);
            if (!entry.has("data") || !entry.has("sig")
                    || !entry.get("data").isJsonObject() || !entry.get("sig").isJsonPrimitive()) {
                return new LinkedHashSet<>();
            }
            JsonObject data = entry.getAsJsonObject("data");
            String storedSig = entry.get("sig").getAsString();
            String expectedSig = hmacHex(playerName + ":" + data.toString());
            if (!constantTimeEquals(storedSig, expectedSig)) {
                return new LinkedHashSet<>();
            }

            Set<String> claimed = new LinkedHashSet<>();
            if (data.has("claimed") && data.get("claimed").isJsonArray()) {
                for (JsonElement e : data.getAsJsonArray("claimed")) {
                    if (e.isJsonPrimitive()) claimed.add(e.getAsString());
                }
            }
            return claimed;
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    public static synchronized void markClaimed(String playerName, Set<String> newGiftIds) throws IOException {
        Set<String> existing = load(playerName);
        existing.addAll(newGiftIds);
        save(playerName, existing);
    }

    private static void save(String playerName, Set<String> claimed) throws IOException {
        Path file = ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve(FILENAME);
        Files.createDirectories(file.getParent());

        JsonObject root;
        if (Files.isRegularFile(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                root = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (Exception e) {
                root = new JsonObject();
            }
        } else {
            root = new JsonObject();
        }

        JsonObject players;
        if (root.has("players") && root.get("players").isJsonObject()) {
            players = root.getAsJsonObject("players");
        } else {
            players = new JsonObject();
            root.add("players", players);
        }

        JsonObject data = new JsonObject();
        JsonArray claimedArr = new JsonArray();
        for (String id : claimed) claimedArr.add(id);
        data.add("claimed", claimedArr);

        String sig = hmacHex(playerName + ":" + data.toString());
        JsonObject entry = new JsonObject();
        entry.add("data", data);
        entry.addProperty("sig", sig);
        players.add(playerName, entry);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] secretBytes() {
        String a = "BarrilMC|gifts|"; String b = "store-v1|"; String c = "kP3_qW7zMxLnVe$2026";
        return (a + b + c).getBytes(StandardCharsets.UTF_8);
    }

    private static String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes(), ALGORITHM));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(sig.length * 2);
            for (byte b : sig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC failed: " + e, e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
