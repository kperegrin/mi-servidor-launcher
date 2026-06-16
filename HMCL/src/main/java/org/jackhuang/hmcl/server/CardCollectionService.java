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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jackhuang.hmcl.util.gson.JsonUtils;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Cloud-backed trading-card collection. The catalogue (which PNGs exist + their public URLs) and
/// each player's inventory both live in Firebase under {@code /cards/...} so jugadores no pueden
/// "givearse" cartas editando ningún fichero local. The PNGs themselves are served from GitHub
/// Releases (or any public HTTPS URL); JavaFX downloads + caches them at first use.
@NotNullByDefault
public final class CardCollectionService {

    /// Sistema de tiradas acumulativas: cada {@link #CLAIM_INTERVAL_MILLIS} ("tick") se acreditan
    /// {@link #CLAIMS_PER_TICK} tiradas de golpe, hasta un máximo de {@link #MAX_PENDING_CLAIMS}.
    /// El tiempo corre aunque el launcher esté cerrado.
    ///
    /// Ritmo actual: **3 tiradas cada 2 horas**, cap a 12 (→ 8 horas para llenar).
    ///
    /// Como el balance puede tomar cualquier valor (no solo múltiplos de 3), el estado no se
    /// puede derivar de un único timestamp: se guarda como (balance explícito,
    /// momento del último tick consolidado) — ver {@link Inventory}.
    public static final long CLAIM_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(2);
    public static final long CLAIM_INTERVAL_HOURS = 2;
    public static final int CLAIMS_PER_TICK = 3;
    public static final int MAX_PENDING_CLAIMS = 12;

    /// Legacy alias kept for any caller that still references the old constants — they refer to
    /// the per-tirada interval, no longer to a flat "one claim per X hours" cooldown.
    public static final long CLAIM_COOLDOWN_HOURS = CLAIM_INTERVAL_HOURS;
    public static final long CLAIM_COOLDOWN_MILLIS = CLAIM_INTERVAL_MILLIS;
    /// Number of cards burned per fusion.
    public static final int FUSION_COST = 3;

    /// In-memory catalogue cache (TTL keeps it fresh enough without hammering Firebase).
    private static final long CATALOG_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private static volatile List<Card> catalogCache = null;
    private static volatile long catalogCachedAt = 0;

    private static final Random RANDOM = new Random();

    /// Lista de cuentas con tiradas infinitas (se saltan el cooldown del cofre). Comparación sin
    /// importar mayúsculas/minúsculas. Añade aquí el nombre exacto de la cuenta de Minecraft del
    /// jugador que quieras hacer "VIP" — verá el contador en "¡Disponible ya!" siempre y podrá
    /// reclamar cuantas veces quiera.
    private static final java.util.Set<String> UNLIMITED_PLAYERS = java.util.Set.of(
            "elkimizg",
            "barrilmc"
            // , "nombredeotroamigo"
    );

    /// Devuelve true si la cuenta puede reclamar sin esperar (whitelist VIP).
    public static boolean hasUnlimitedClaims(String playerName) {
        return UNLIMITED_PLAYERS.contains(playerName.toLowerCase(Locale.ROOT));
    }

    private CardCollectionService() {
    }

    public enum Rarity {
        COMUN("comun", "Comun", "#9aa3b8", 50),
        POCO_COMUN("poco_comun", "Poco comun", "#45d483", 30),
        RARA("rara", "Rara", "#4ea6ff", 15),
        MITICA("mitica", "Mitica", "#b66cff", 4),
        LEYENDA("leyenda", "Leyenda", "#ffaa22", 1),
        // INFINITO: rareza secreta. weight=0 → nunca sale en el roll de rarezas estándar;
        // tirada aparte de 1 entre 100.000 en pickWeightedRandom. No se muestra en la Pokédex.
        INFINITO("infinito", "Infinito", "#ff00ff", 0);

        private final String folder;
        private final String displayName;
        private final String color;
        private final int weight;

        Rarity(String folder, String displayName, String color, int weight) {
            this.folder = folder;
            this.displayName = displayName;
            this.color = color;
            this.weight = weight;
        }

        public String getFolder() { return folder; }
        public String getDisplayName() { return displayName; }
        public String getColor() { return color; }
        public int getWeight() { return weight; }

        public @Nullable Rarity getFusionResultRarity() {
            // LEYENDA es el tope que se puede conseguir por fusión. INFINITO no se sube nunca:
            // solo sale por la tirada de 1/100.000 o por el botón "Juan el Pro" del master admin.
            if (this == LEYENDA || this == INFINITO) return null;
            int next = ordinal() + 1;
            Rarity[] rarities = values();
            Rarity result = next < rarities.length ? rarities[next] : null;
            // Defensa extra: nunca devolver INFINITO como recompensa de fusión, sea lo que sea.
            return result == INFINITO ? null : result;
        }

        public static @Nullable Rarity fromKey(String name) {
            if (name == null) {
                return null;
            }
            String lc = name.trim().toLowerCase(Locale.ROOT);
            switch (lc) {
                case "0":
                case "infinito":
                case "infinity":
                case "secret":
                    return INFINITO;
                case "1":
                case "leyenda":
                case "legendaria":
                case "legendary":
                    return LEYENDA;
                case "2":
                case "mitica":
                case "miticas":
                case "mythic":
                case "epica":
                case "epicas":
                    return MITICA;
                case "3":
                    return RARA;
                case "4":
                case "poco_comun":
                case "poco-comun":
                case "poco comun":
                case "poco comunes":
                case "uncommon":
                    return POCO_COMUN;
                case "5":
                    return COMUN;
                default:
                    break;
            }
            for (Rarity r : values()) {
                if (r.folder.equals(lc)) {
                    return r;
                }
            }
            return fromCardName(lc);
        }

        public static @Nullable Rarity fromCardName(String name) {
            if (name == null) {
                return null;
            }
            String cleaned = name.trim();
            int slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
            if (slash >= 0) {
                cleaned = cleaned.substring(slash + 1);
            }
            if (cleaned.isEmpty()) {
                return null;
            }
            switch (cleaned.charAt(0)) {
                case '1': return LEYENDA;
                case '2': return MITICA;
                case '3': return RARA;
                case '4': return POCO_COMUN;
                case '5': return COMUN;
                default: return null;
            }
        }
    }

    /// One card definition in the catalogue. {@link #id} is the Firebase push-id; {@link #url} is
    /// the public HTTPS URL of the PNG.
    public static final class Card {
        private final String id;
        private final String name;
        private final Rarity rarity;
        private final String url;
        private final boolean secret;

        Card(String id, String name, Rarity rarity, String url, boolean secret) {
            this.id = id;
            this.name = name;
            this.rarity = rarity;
            this.url = url;
            this.secret = secret;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public Rarity getRarity() { return rarity; }
        public String getUrl() { return url; }
        /// Las cartas secretas no aparecen en la Pokédex aunque no las tengas conseguidas.
        public boolean isSecret() { return secret; }
    }

    /// A player's collection. {@link #counts} mapea las cartas que tiene ahora (count > 0).
    /// {@link #seen} es el set de la Pokédex: cada carta que ha tenido alguna vez. El sistema de
    /// tiradas usa {@link #claimsBalance} (saldo explícito 0..MAX) y
    /// {@link #claimsLastTickEpochMillis} (momento del último tick consolidado): el saldo real
    /// disponible en cualquier instante es {@code min(MAX, balance + ticksDesdeLastTick * 3)}.
    public static final class Inventory {
        private final int claimsBalance;
        private final long claimsLastTickEpochMillis;
        private final LinkedHashMap<String, Integer> counts;
        private final java.util.LinkedHashSet<String> seen;

        Inventory(int claimsBalance,
                  long claimsLastTickEpochMillis,
                  LinkedHashMap<String, Integer> counts,
                  java.util.LinkedHashSet<String> seen) {
            this.claimsBalance = claimsBalance;
            this.claimsLastTickEpochMillis = claimsLastTickEpochMillis;
            this.counts = counts;
            this.seen = seen;
        }

        public int getClaimsBalance() { return claimsBalance; }
        public long getClaimsLastTickEpochMillis() { return claimsLastTickEpochMillis; }
        public LinkedHashMap<String, Integer> getCounts() { return counts; }
        public java.util.LinkedHashSet<String> getSeen() { return seen; }
        public int getTotal() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    // ============================================================================================
    //                                          Catalogue
    // ============================================================================================

    /// Lists every card currently in the Firebase catalogue (cached for {@value CATALOG_TTL_MS}ms).
    public static List<Card> listAllCards() {
        long now = System.currentTimeMillis();
        List<Card> cached = catalogCache;
        if (cached != null && now - catalogCachedAt < CATALOG_TTL_MS) {
            return cached;
        }
        List<Card> fresh = fetchCatalog();
        catalogCache = fresh;
        catalogCachedAt = now;
        return fresh;
    }

    /// Forces a catalogue refresh on the next {@link #listAllCards()} call. Useful right after the
    /// admin uploads a new card.
    public static void invalidateCatalog() {
        catalogCache = null;
        catalogCachedAt = 0;
    }

    private static List<Card> fetchCatalog() {
        List<Card> fromGithub = fetchCatalogFromGithubPages();
        if (!fromGithub.isEmpty()) {
            return fromGithub;
        }
        return fetchCatalogFromCloud();
    }

    private static List<Card> fetchCatalogFromGithubPages() {
        // Estrategia de fetch en cascada:
        //   1) raw.githubusercontent.com (URL "natural" del manifest). Suele propagar commits en
        //      pocos minutos, y el cliente puede invalidar su CDN con un query string.
        //   2) jsdelivr.net como fallback (espejo de GitHub). Cuando el origin de raw est\u00E1
        //      bloqueado por DNS o saturado, jsdelivr suele estar disponible \u2014 pero ojo: su
        //      cache de @branch se queda atr\u00E1s cuando su API de purga falla, por eso NO es la
        //      primera opci\u00F3n.
        String base = ServerLauncherConfig.MANIFEST_URL;
        String rawUrl = URI.create(base).resolve("cards.json").toString();
        List<Card> result = tryFetch(rawUrl);
        if (!result.isEmpty()) return result;

        String jsdelivrUrl = jsdelivrFor(base, "cards.json");
        if (jsdelivrUrl != null) {
            result = tryFetch(jsdelivrUrl);
            if (!result.isEmpty()) return result;
        }
        return Collections.emptyList();
    }

    private static List<Card> tryFetch(String url) {
        url += (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
        try (InputStream input = LauncherUpdater.openHttps(url)) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
                json = json.substring(1);
            }
            return parseCatalog(JsonUtils.GSON.fromJson(json, JsonElement.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /// Converts a raw.githubusercontent.com URL into the equivalent jsdelivr.net URL pointing at
    /// {@code fileName} inside the same launcher folder. Returns null if the input isn't a known
    /// raw URL format.
    private static @Nullable String jsdelivrFor(String rawUrl, String fileName) {
        // Expected: https://raw.githubusercontent.com/<owner>/<repo>/<branch>/launcher/<file>
        String prefix = "https://raw.githubusercontent.com/";
        if (rawUrl == null || !rawUrl.startsWith(prefix)) {
            return null;
        }
        String[] parts = rawUrl.substring(prefix.length()).split("/", 5);
        if (parts.length < 4) {
            return null;
        }
        String owner = parts[0];
        String repo = parts[1];
        String branch = parts[2];
        // parts[3] is "launcher" (the folder); we replace the file part with our own.
        String folder = parts[3];
        return "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + folder + "/" + fileName;
    }

    private static List<Card> fetchCatalogFromCloud() {
        if (!BarrilmcCloud.isConfigured()) {
            return Collections.emptyList();
        }
        try {
            JsonElement element = BarrilmcCloud.get("cards/catalog", null);
            return parseCatalog(element);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<Card> parseCatalog(@Nullable JsonElement element) {
        if (element == null) {
            return Collections.emptyList();
        }

        List<Card> result = new ArrayList<>();
        if (element.isJsonObject()) {
            JsonObject root = element.getAsJsonObject();
            if (root.has("cards")) {
                appendCatalog(result, root.get("cards"));
            } else {
                appendCatalogObject(result, root);
            }
        } else if (element.isJsonArray()) {
            appendCatalog(result, element);
        }

        result.sort((a, b) -> {
            int byRarity = Integer.compare(b.getRarity().ordinal(), a.getRarity().ordinal());
            return byRarity != 0 ? byRarity : a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    private static void appendCatalog(List<Card> result, JsonElement element) {
        if (element == null) {
            return;
        }
        if (element.isJsonObject()) {
            appendCatalogObject(result, element.getAsJsonObject());
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject obj = item.getAsJsonObject();
                String id = readPrimitive(obj, "id", "");
                if (id.isBlank()) {
                    id = safeCardId(readPrimitive(obj, "name", ""));
                }
                Card card = parseCard(id, obj);
                if (card != null) {
                    result.add(card);
                }
            }
        }
    }

    private static void appendCatalogObject(List<Card> result, JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Card card = parseCard(entry.getKey(), entry.getValue().getAsJsonObject());
            if (card != null) {
                result.add(card);
            }
        }
    }

    private static @Nullable Card parseCard(String id, JsonObject obj) {
        String name = readPrimitive(obj, "name", id);
        String rarityKey = readPrimitive(obj, "rarity", "");
        String url = readPrimitive(obj, "url", "");
        String path = readPrimitive(obj, "path", "");
        Rarity rarity = Rarity.fromKey(rarityKey);
        if (rarity == null) rarity = Rarity.fromCardName(id);
        if (rarity == null) rarity = Rarity.fromCardName(name);
        if (rarity == null) rarity = Rarity.fromCardName(path);
        if (rarity == null || url.isBlank()) {
            return null;
        }
        // El campo "secret": true marca cartas que no aparecen en la Pokédex aunque no las tengas.
        boolean secret = obj.has("secret") && obj.get("secret").isJsonPrimitive()
                && obj.get("secret").getAsBoolean();
        // INFINITO siempre es secret (regla de diseño), incluso si el JSON no lo marca.
        if (rarity == Rarity.INFINITO) secret = true;
        return new Card(id, name, rarity, url, secret);
    }

    private static String readPrimitive(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static String safeCardId(String name) {
        String id = name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isBlank() ? "card" : id;
    }

    public static @Nullable Card findCard(String id) {
        for (Card card : listAllCards()) {
            if (card.getId().equals(id)) {
                return card;
            }
        }
        return null;
    }

    // ============================================================================================
    //                                         Inventory
    // ============================================================================================

    /// Loads the player's inventory from the signed local store. Tampered/missing entries return
    /// an empty inventory (the claim cooldown effectively resets but the player loses any
    /// hand-edited cards).
    public static Inventory loadInventory(String playerName) {
        return CardInventoryStore.load(playerName);
    }

    private static boolean saveInventory(String playerName, Inventory inv) {
        try {
            CardInventoryStore.save(playerName, inv);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ============================================================================================
    //                                         Daily / fusion
    // ============================================================================================

    /// Tiradas disponibles AHORA MISMO: saldo guardado + ticks pendientes desde el último tick
    /// consolidado, capped a {@link #MAX_PENDING_CLAIMS}. Calculo puro, no guarda nada.
    public static int getAvailableClaims(String playerName) {
        if (hasUnlimitedClaims(playerName)) {
            return MAX_PENDING_CLAIMS;
        }
        Inventory inv = loadInventory(playerName);
        return computeAvailableClaims(inv, System.currentTimeMillis());
    }

    private static int computeAvailableClaims(Inventory inv, long now) {
        int balance = inv.getClaimsBalance();
        long lastTick = inv.getClaimsLastTickEpochMillis();
        if (lastTick <= 0 || lastTick > now) {
            return Math.max(0, Math.min(MAX_PENDING_CLAIMS, balance));
        }
        long ticksPassed = (now - lastTick) / CLAIM_INTERVAL_MILLIS;
        long total = (long) balance + ticksPassed * CLAIMS_PER_TICK;
        if (total > MAX_PENDING_CLAIMS) total = MAX_PENDING_CLAIMS;
        if (total < 0) total = 0;
        return (int) total;
    }

    /// Milisegundos hasta el siguiente tick (acreditará {@link #CLAIMS_PER_TICK} tiradas de
    /// golpe). Devuelve 0 si el jugador ya está al cap (no acumula más) o es VIP.
    public static long timeUntilNextClaim(String playerName) {
        if (hasUnlimitedClaims(playerName)) {
            return 0L;
        }
        Inventory inv = loadInventory(playerName);
        long now = System.currentTimeMillis();
        if (computeAvailableClaims(inv, now) >= MAX_PENDING_CLAIMS) {
            return 0L;
        }
        long lastTick = inv.getClaimsLastTickEpochMillis();
        if (lastTick <= 0 || lastTick > now) {
            return CLAIM_INTERVAL_MILLIS;
        }
        long elapsedInCurrentTick = (now - lastTick) % CLAIM_INTERVAL_MILLIS;
        return CLAIM_INTERVAL_MILLIS - elapsedInCurrentTick;
    }

    /// Consolida los ticks pendientes (los aplica al balance y avanza lastTick) y devuelve el
    /// inventario resultante. Pura: no guarda nada.
    private static Inventory consolidateTicks(Inventory inv, long now) {
        long lastTick = inv.getClaimsLastTickEpochMillis();
        if (lastTick <= 0 || lastTick > now) {
            // Primera vez o estado raro: anclamos el reloj en "now" sin tocar el balance.
            return new Inventory(inv.getClaimsBalance(), now, inv.getCounts(), inv.getSeen());
        }
        long ticksPassed = (now - lastTick) / CLAIM_INTERVAL_MILLIS;
        if (ticksPassed <= 0) {
            return inv; // nada que consolidar
        }
        long newBalance = (long) inv.getClaimsBalance() + ticksPassed * CLAIMS_PER_TICK;
        if (newBalance > MAX_PENDING_CLAIMS) newBalance = MAX_PENDING_CLAIMS;
        long newLastTick = lastTick + ticksPassed * CLAIM_INTERVAL_MILLIS;
        return new Inventory((int) newBalance, newLastTick, inv.getCounts(), inv.getSeen());
    }

    /// Consume una tirada (si hay) y devuelve la carta ganada, o null si no había tiradas
    /// disponibles. Los VIP no gastan saldo: siempre pueden reclamar.
    public static synchronized @Nullable Card claimDaily(String playerName) throws IOException {
        Inventory inv = loadInventory(playerName);
        boolean unlimited = hasUnlimitedClaims(playerName);
        long now = System.currentTimeMillis();

        Inventory consolidated = consolidateTicks(inv, now);
        if (!unlimited && consolidated.getClaimsBalance() <= 0) {
            // Aunque no haya tiradas, guardamos la consolidación si la hubo (lastTick avanzado)
            // para no perder progreso. Si no hubo, esto es no-op.
            if (consolidated != inv) {
                saveInventory(playerName, consolidated);
            }
            return null;
        }

        Card chosen = pickWeightedRandom();
        if (chosen == null) {
            return null;
        }
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(consolidated.getCounts());
        counts.merge(chosen.getId(), 1, Integer::sum);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(consolidated.getSeen());
        seen.add(chosen.getId());

        // VIP: no se descuenta saldo. No-VIP: -1 al balance consolidado.
        int newBalance = unlimited
                ? consolidated.getClaimsBalance()
                : consolidated.getClaimsBalance() - 1;
        Inventory next = new Inventory(newBalance, consolidated.getClaimsLastTickEpochMillis(),
                counts, seen);
        if (!saveInventory(playerName, next)) {
            throw new IOException("No se pudo guardar el inventario local.");
        }
        return chosen;
    }

    public static synchronized @Nullable Card fuse(String playerName, List<String> spentCardIds) throws IOException {
        if (spentCardIds.size() != FUSION_COST) {
            return null;
        }
        Inventory inv = loadInventory(playerName);
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(inv.getCounts());

        LinkedHashMap<String, Integer> burn = new LinkedHashMap<>();
        for (String id : spentCardIds) burn.merge(id, 1, Integer::sum);
        Rarity sourceRarity = null;
        for (Map.Entry<String, Integer> e : burn.entrySet()) {
            int owned = counts.getOrDefault(e.getKey(), 0);
            if (owned < e.getValue()) {
                return null;
            }
            Card card = findCard(e.getKey());
            if (card == null) {
                return null;
            }
            if (sourceRarity == null) {
                sourceRarity = card.getRarity();
            } else if (sourceRarity != card.getRarity()) {
                return null;
            }
        }

        Rarity rewardRarity = sourceRarity == null ? null : sourceRarity.getFusionResultRarity();
        if (rewardRarity == null) {
            return null;
        }

        for (Map.Entry<String, Integer> e : burn.entrySet()) {
            int newCount = counts.get(e.getKey()) - e.getValue();
            if (newCount <= 0) counts.remove(e.getKey());
            else counts.put(e.getKey(), newCount);
        }

        Card reward = pickRandomByRarity(rewardRarity);
        if (reward == null) {
            // Nothing on the catalogue: bail out without saving so the player keeps their cards.
            return null;
        }
        counts.merge(reward.getId(), 1, Integer::sum);
        // Pokédex: la nueva carta también queda registrada como "vista".
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(inv.getSeen());
        seen.add(reward.getId());
        if (!saveInventory(playerName, new Inventory(
                inv.getClaimsBalance(), inv.getClaimsLastTickEpochMillis(), counts, seen))) {
            throw new IOException("No se pudo guardar el inventario local.");
        }
        return reward;
    }

    private static @Nullable Card pickRandomByRarity(Rarity rarity) {
        List<Card> pool = new ArrayList<>();
        for (Card card : listAllCards()) {
            if (card.getRarity() == rarity) {
                pool.add(card);
            }
        }
        return pool.isEmpty() ? null : pool.get(RANDOM.nextInt(pool.size()));
    }

    private static @Nullable Card pickWeightedRandom() {
        List<Card> all = listAllCards();
        if (all.isEmpty()) {
            return null;
        }

        // Tirada SECRETA: 1 entre 100.000 (0.001 %) de que toque una carta INFINITO.
        // Si hay alguna disponible y el dado cae, devolvemos directamente sin pasar por el roll
        // de rarezas normales.
        if (RANDOM.nextInt(100_000) < 1) {
            List<Card> infinitos = new ArrayList<>();
            for (Card c : all) {
                if (c.getRarity() == Rarity.INFINITO) infinitos.add(c);
            }
            if (!infinitos.isEmpty()) {
                return infinitos.get(RANDOM.nextInt(infinitos.size()));
            }
        }

        Map<Rarity, List<Card>> byRarity = new java.util.EnumMap<>(Rarity.class);
        for (Card card : all) {
            // INFINITO está fuera del roll estándar: solo aparece por la tirada secreta de arriba
            // o por claimSpecificCard (botón admin).
            if (card.getRarity() == Rarity.INFINITO) continue;
            byRarity.computeIfAbsent(card.getRarity(), k -> new ArrayList<>()).add(card);
        }

        Rarity[] order = { Rarity.LEYENDA, Rarity.MITICA, Rarity.RARA, Rarity.POCO_COMUN, Rarity.COMUN };
        int totalWeight = 0;
        for (Rarity r : order) totalWeight += r.getWeight();
        int roll = RANDOM.nextInt(totalWeight);
        Rarity picked = Rarity.COMUN;
        for (Rarity r : order) {
            roll -= r.getWeight();
            if (roll < 0) { picked = r; break; }
        }

        Rarity[] fallback = { picked, Rarity.COMUN, Rarity.POCO_COMUN, Rarity.RARA, Rarity.MITICA, Rarity.LEYENDA };
        for (Rarity r : fallback) {
            List<Card> pool = byRarity.get(r);
            if (pool != null && !pool.isEmpty()) {
                return pool.get(RANDOM.nextInt(pool.size()));
            }
        }
        // Último fallback: cualquier carta NO secreta.
        List<Card> nonSecret = new ArrayList<>();
        for (Card c : all) if (c.getRarity() != Rarity.INFINITO) nonSecret.add(c);
        if (nonSecret.isEmpty()) return null;
        Collections.shuffle(nonSecret, RANDOM);
        return nonSecret.get(0);
    }

    // ============================================================================================
    //                                    Admin tools (whitelist)
    // ============================================================================================

    /// Solo el "master admin" (ElKimiZG, fundador del proyecto) puede usar herramientas con
    /// efecto irreversible — como forzar una carta concreta. Barrilmc sigue siendo VIP general
    /// pero no master admin: no debería poder farmear infinitos con esto.
    public static boolean isMasterAdmin(String playerName) {
        return playerName != null && "elkimizg".equals(playerName.toLowerCase(Locale.ROOT));
    }

    /// Da al jugador una carta concreta del catálogo, saltándose el random. Pensado para el
    /// botón "Forzar Juan el Pro" del master admin. Si la carta es INFINITO, también se marca
    /// como vista en la Pokédex del propio admin para que la pueda ver luego ahí... pero como
    /// las INFINITO no se muestran, eso es solo para coherencia.
    public static synchronized @Nullable Card claimSpecificCard(String playerName, String cardId) throws IOException {
        if (!isMasterAdmin(playerName)) {
            return null; // solo master admin
        }
        Card chosen = findCard(cardId);
        if (chosen == null) {
            return null;
        }
        Inventory inv = loadInventory(playerName);
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(inv.getCounts());
        counts.merge(chosen.getId(), 1, Integer::sum);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(inv.getSeen());
        seen.add(chosen.getId());
        if (!saveInventory(playerName,
                new Inventory(inv.getClaimsBalance(), inv.getClaimsLastTickEpochMillis(), counts, seen))) {
            throw new IOException("No se pudo guardar el inventario.");
        }
        return chosen;
    }

    /// Publishes a "gift" entry in Firebase under {@code /cards/gifts/<pushId>}. Every other
    /// player picks it up on their next chest refresh and gets +1 ticket. Only callable by
    /// admins on the whitelist; returns false otherwise (and doesn't publish anything).
    public static boolean giveAllPlayersOneClaim(String adminName) {
        if (!hasUnlimitedClaims(adminName) || !BarrilmcCloud.isConfigured()) {
            return false;
        }
        JsonObject gift = new JsonObject();
        gift.addProperty("from", adminName);
        JsonObject sv = new JsonObject();
        sv.addProperty(".sv", "timestamp");
        gift.add("ts", sv);
        try {
            BarrilmcCloud.post("cards/gifts", gift.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /// Reads every pending gift from Firebase and applies +1 ticket per gift to {@code playerName}.
    /// Each gift is only ever applied once per player (tracked in {@code claimed-gifts.json}). The
    /// total available tickets stays capped at {@link #MAX_PENDING_CLAIMS}. Returns the number of
    /// gifts that were applied in this call (0 if none were pending or there was a fetch error).
    public static synchronized int applyPendingGifts(String playerName) {
        if (!BarrilmcCloud.isConfigured()) {
            return 0;
        }
        Set<String> alreadyClaimed = ClaimedGiftsStore.load(playerName);

        JsonElement gifts;
        try {
            gifts = BarrilmcCloud.get("cards/gifts", null);
        } catch (Exception e) {
            return 0;
        }
        if (gifts == null || !gifts.isJsonObject()) {
            return 0;
        }

        Set<String> pending = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> e : gifts.getAsJsonObject().entrySet()) {
            if (!alreadyClaimed.contains(e.getKey())) {
                pending.add(e.getKey());
            }
        }
        if (pending.isEmpty()) {
            return 0;
        }

        Inventory inv = loadInventory(playerName);
        long now = System.currentTimeMillis();
        // Consolida primero los ticks pasados, luego aplica +N al balance, respetando el cap.
        Inventory consolidated = consolidateTicks(inv, now);
        long newBalance = (long) consolidated.getClaimsBalance() + pending.size();
        if (newBalance > MAX_PENDING_CLAIMS) newBalance = MAX_PENDING_CLAIMS;
        Inventory next = new Inventory((int) newBalance, consolidated.getClaimsLastTickEpochMillis(),
                consolidated.getCounts(), consolidated.getSeen());
        if (!saveInventory(playerName, next)) {
            return 0; // No pudimos guardar; los regalos se intentarán otra vez en el próximo refresh.
        }

        try {
            ClaimedGiftsStore.markClaimed(playerName, pending);
        } catch (IOException e) {
            // Si falla, la próxima vez los regalos volverán a "pendientes" y se re-aplicarán →
            // el jugador tendría tiradas duplicadas. Para evitarlo, revertimos el inventario.
            saveInventory(playerName, inv);
            return 0;
        }
        return pending.size();
    }

    /// Wipes the player's inventory, Pokédex AND the claimed-gifts list, and resets the claims
    /// clock so they start over. Intended only for admins testing the system.
    public static synchronized void resetInventory(String playerName) throws IOException {
        if (!saveInventory(playerName,
                new Inventory(0, System.currentTimeMillis(),
                        new LinkedHashMap<>(),
                        new java.util.LinkedHashSet<>()))) {
            throw new IOException("No se pudo reiniciar el inventario.");
        }
        // Borra también el registro de gifts ya reclamados de ese jugador, así si vuelves a
        // probar regalos te empiezan a llegar desde cero.
        try {
            java.nio.file.Path file = ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve("claimed-gifts.json");
            if (java.nio.file.Files.isRegularFile(file)) {
                JsonElement parsed = com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    JsonObject root = parsed.getAsJsonObject();
                    if (root.has("players") && root.get("players").isJsonObject()) {
                        root.getAsJsonObject("players").remove(playerName);
                        java.nio.file.Files.writeString(file, root.toString(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception ignored) {
            // Borrar gifts es un extra; si falla no es fatal.
        }
    }
}
