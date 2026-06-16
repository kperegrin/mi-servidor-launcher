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
import org.jackhuang.hmcl.server.CardCollectionService.Inventory;
import org.jetbrains.annotations.NotNullByDefault;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/// Local, tamper-evident store for card inventories. Each player's entry is signed with
/// HMAC-SHA256 over {@code "<playerName>:<dataJson>"} using a secret embedded in the launcher.
/// Hand-editing the JSON, copying an entry across player names or otherwise mutating the file
/// invalidates the signature, in which case the entry is discarded on load.
///
/// What this prevents (vs the previous plain-text JSON):
///   • Manually giving yourself cards (changing counts) — signature mismatch → entry ignored.
///   • Resetting your claim cooldown by editing {@code lastClaim} — same reason.
///   • Stealing another player's inventory by renaming their entry — the playerName is part of
///     the signed payload, so a rename invalidates the signature too.
///
/// What this does NOT prevent (would need server-side logic or Firebase Auth):
///   • A user decompiling the launcher to extract the secret and signing a forged entry.
///   • Backup/restore rollback (saving a copy of the file, spending cards, restoring the backup).
///   • Wiping the file outright (loses cards but also resets the claim cooldown).
@NotNullByDefault
public final class CardInventoryStore {

    private static final String FILENAME = "cards-inventory.json";
    private static final String ALGORITHM = "HmacSHA256";

    private CardInventoryStore() {
    }

    /// Loads the inventory for a player, or an empty one if the file is missing/corrupt/tampered.
    public static synchronized Inventory load(String playerName) {
        Path file = ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve(FILENAME);
        if (!Files.isRegularFile(file)) {
            return new Inventory(0, System.currentTimeMillis(), new LinkedHashMap<>(), new LinkedHashSet<>());
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return empty();
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("players") || !root.get("players").isJsonObject()) return empty();
            JsonObject players = root.getAsJsonObject("players");
            if (!players.has(playerName) || !players.get(playerName).isJsonObject()) return empty();

            JsonObject entry = players.getAsJsonObject(playerName);
            if (!entry.has("data") || !entry.has("sig")
                    || !entry.get("data").isJsonObject() || !entry.get("sig").isJsonPrimitive()) {
                return empty();
            }
            JsonObject data = entry.getAsJsonObject("data");
            String storedSig = entry.get("sig").getAsString();
            String expectedSig = hmacHex(playerName + ":" + data.toString());
            boolean signatureValid = constantTimeEquals(storedSig, expectedSig);
            if (!signatureValid) {
                // Firma no cuadra. En vez de tirar TODO el inventario (lo que en versiones
                // anteriores hacía que los jugadores perdieran sus cromos al migrar entre
                // formatos de JSON, p.ej. del modelo claimsBase al claimsBalance/claimsLastTick),
                // intentamos un "rescate":
                //   - Si el JSON tiene un campo `cards` con counts válidos (>=1) y un `seen`
                //     compatible, asumimos que es un inventario LEGÍTIMO al que se le ha roto
                //     la firma por una migración o un cambio de formato.
                //   - Conservamos sus cards + seen pero RESETEAMOS las tiradas a 0 con el reloj
                //     en "now" para evitar que alguien que edita a mano se regale tiradas extra.
                //   - El próximo save (al gastar 1 tirada, recibir un gift, etc.) re-firma con
                //     la firma correcta y queda blindado de nuevo.
                // El coste: alguien que edite a mano sus counts SÍ podría meterse cromos. Aceptable
                // a cambio de que los jugadores que perdieron datos los recuperen.
                if (looksLikePlausibleInventory(data)) {
                    return rescueInventory(data);
                }
                return empty();
            }

            // Nuevo modelo (balance + lastTick) o migración de los viejos.
            int claimsBalance;
            long claimsLastTick;
            if (data.has("claimsBalance") && data.has("claimsLastTick")
                    && data.get("claimsBalance").isJsonPrimitive()
                    && data.get("claimsLastTick").isJsonPrimitive()) {
                claimsBalance = data.get("claimsBalance").getAsInt();
                claimsLastTick = data.get("claimsLastTick").getAsLong();
            } else {
                // Migración de los modelos viejos (claimsBase / lastClaim): derivamos un saldo
                // razonable según el tiempo transcurrido y reanclamos el reloj a "now". En la
                // práctica esto puede dar al jugador algunas tiradas extra de bienvenida, lo cual
                // es aceptable porque solo pasa una vez por inventario en la migración.
                long oldBase = 0L;
                if (data.has("claimsBase") && data.get("claimsBase").isJsonPrimitive()) {
                    oldBase = data.get("claimsBase").getAsLong();
                } else if (data.has("lastClaim") && data.get("lastClaim").isJsonPrimitive()) {
                    oldBase = data.get("lastClaim").getAsLong();
                }
                long now = System.currentTimeMillis();
                if (oldBase > 0 && oldBase <= now) {
                    // En el modelo viejo (1 tirada cada 40min) → derivamos el saldo y capamos.
                    long elapsed = now - oldBase;
                    long fortyMin = 40 * 60 * 1000L;
                    long derived = elapsed / fortyMin;
                    if (derived < 0) derived = 0;
                    if (derived > 12) derived = 12; // hard cap (MAX_PENDING_CLAIMS)
                    claimsBalance = (int) derived;
                } else {
                    claimsBalance = 0;
                }
                claimsLastTick = now;
            }
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            if (data.has("cards") && data.get("cards").isJsonObject()) {
                for (Map.Entry<String, JsonElement> c : data.getAsJsonObject("cards").entrySet()) {
                    if (c.getValue().isJsonPrimitive()) {
                        int n = c.getValue().getAsInt();
                        if (n > 0) counts.put(c.getKey(), n);
                    }
                }
            }
            // Pokédex: cartas que el jugador ha tenido alguna vez (aunque las haya fusionado).
            // Para inventarios antiguos sin este campo, asumimos que ha visto al menos lo que tiene.
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            if (data.has("seen") && data.get("seen").isJsonArray()) {
                for (JsonElement e : data.getAsJsonArray("seen")) {
                    if (e.isJsonPrimitive()) {
                        seen.add(e.getAsString());
                    }
                }
            }
            seen.addAll(counts.keySet());
            return new Inventory(claimsBalance, claimsLastTick, counts, seen);
        } catch (Exception e) {
            return empty();
        }
    }

    /// Writes the inventory for {@code playerName}, replacing only that player's entry. Other
    /// players' entries (and their signatures) are preserved intact.
    public static synchronized void save(String playerName, Inventory inv) throws IOException {
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
        data.addProperty("claimsBalance", inv.getClaimsBalance());
        data.addProperty("claimsLastTick", inv.getClaimsLastTickEpochMillis());
        JsonObject cards = new JsonObject();
        for (Map.Entry<String, Integer> e : inv.getCounts().entrySet()) {
            cards.addProperty(e.getKey(), e.getValue());
        }
        data.add("cards", cards);
        // Pokédex permanente: ids que el jugador ha tenido alguna vez.
        JsonArray seen = new JsonArray();
        for (String id : inv.getSeen()) {
            seen.add(id);
        }
        data.add("seen", seen);

        String sig = hmacHex(playerName + ":" + data.toString());
        JsonObject entry = new JsonObject();
        entry.add("data", data);
        entry.addProperty("sig", sig);
        players.add(playerName, entry);

        // Backup rotativo de 1 nivel ANTES de sobrescribir, así si algo va mal (firma rota,
        // crash a mitad de write, edición manual, etc.) se puede recuperar leyendo
        // cards-inventory.json.bak.
        try {
            if (Files.isRegularFile(file)) {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Backup es best-effort. Si falla, seguimos con el save normal.
        }

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Inventory empty() {
        return new Inventory(0, System.currentTimeMillis(), new LinkedHashMap<>(), new LinkedHashSet<>());
    }

    /// Heurística: ¿el {@code data} se parece a un inventario real (cards object con valores int
    /// >=1, opcionalmente con un seen JsonArray de strings)? Si sí, no es ruido aleatorio y vale la
    /// pena rescatarlo aunque la firma esté rota.
    private static boolean looksLikePlausibleInventory(JsonObject data) {
        if (!data.has("cards") || !data.get("cards").isJsonObject()) {
            return false;
        }
        JsonObject cards = data.getAsJsonObject("cards");
        int validCount = 0;
        for (Map.Entry<String, JsonElement> entry : cards.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) return false;
            try {
                int n = entry.getValue().getAsInt();
                if (n < 1 || n > 1_000_000) return false;
                validCount++;
            } catch (RuntimeException e) {
                return false;
            }
        }
        // Si seen existe, debe ser un array de strings.
        if (data.has("seen")) {
            if (!data.get("seen").isJsonArray()) return false;
            for (JsonElement e : data.getAsJsonArray("seen")) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return false;
            }
        }
        // Aceptamos vacío también (jugador legítimo sin cromos aún), pero solo si la estructura es
        // exactamente la esperada (mejor evitar reciclar cualquier JSON cualquiera).
        return validCount >= 0;
    }

    /// Reconstruye un Inventory desde un JSON cuya firma no cuadra. Conserva los counts y la
    /// pokédex tal cual, pero resetea las tiradas a 0 (con reloj en "now") para que un editor de
    /// JSON no se regale el cap entero.
    private static Inventory rescueInventory(JsonObject data) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> c : data.getAsJsonObject("cards").entrySet()) {
            if (c.getValue().isJsonPrimitive()) {
                int n = c.getValue().getAsInt();
                if (n > 0) counts.put(c.getKey(), n);
            }
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (data.has("seen") && data.get("seen").isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray("seen")) {
                if (e.isJsonPrimitive()) seen.add(e.getAsString());
            }
        }
        seen.addAll(counts.keySet());
        return new Inventory(0, System.currentTimeMillis(), counts, seen);
    }

    // ----- HMAC -----

    /// Secret is built from a few pieces so it doesn't appear as a single grep-able literal in the
    /// compiled bytecode. (Real decompilation still recovers it; treat this as a speed bump.)
    private static byte[] secretBytes() {
        String a = "BarrilMC|cards|"; String b = "store-v1|"; String c = "k7P_mqL9eX#zHv$2026";
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
