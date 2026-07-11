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

import javafx.scene.image.Image;
import org.jackhuang.hmcl.server.CardCollectionService.Card;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Two-level cache for card art: an in-memory {@code Map<cardId, Image>} for the current session
/// and a persistent on-disk cache under {@code <launcher>/cards-cache/} so each PNG is only
/// downloaded once on the whole machine. The first call to {@link #getImage(Card)} for a card not
/// yet on disk returns an async-loading JavaFX Image (so the UI doesn't block) while a parallel
/// background download copies the bytes to the disk cache, ready for instant reuse next time.
@NotNullByDefault
public final class CardImageCache {

    private static final ConcurrentHashMap<String, Image> MEMORY = new ConcurrentHashMap<>();

    /// Bounded pool: enough threads for parallel downloads without flooding the network.
    private static final ExecutorService DOWNLOADER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "barrilmc-card-image-loader");
        t.setDaemon(true);
        return t;
    });

    private CardImageCache() {
    }

    private static Path cacheDir() {
        Path dir = ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve("cards-cache");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    private static Path cachedFile(String cardId) {
        // Sanitize for filename safety: keep alphanumerics/_-, replace anything else with '_'.
        StringBuilder safe = new StringBuilder(cardId.length());
        for (char c : cardId.toCharArray()) {
            safe.append((Character.isLetterOrDigit(c) || c == '_' || c == '-') ? c : '_');
        }
        return cacheDir().resolve(safe + ".png");
    }

    /// Returns a JavaFX {@link Image} for the card. Order of preference:
    /// 1. Already cached in memory → return as-is (instant).
    /// 2. On disk → load from {@code file://...} synchronously (very fast, no blocking).
    /// 3. Not yet cached → start a background download to disk AND return a JavaFX
    ///    async-loading Image from the URL so something shows up in the UI while the
    ///    download is in flight.
    public static Image getImage(Card card) {
        Image hit = MEMORY.get(card.getId());
        if (hit != null && !hit.isError()) {
            return hit;
        }

        Path file = cachedFile(card.getId());
        if (Files.isRegularFile(file)) {
            try {
                Image fromDisk = new Image(file.toUri().toString(), false);
                if (!fromDisk.isError()) {
                    MEMORY.put(card.getId(), fromDisk);
                    return fromDisk;
                }
            } catch (Exception ignored) {
            }
        }

        // Not on disk yet. Show an async-loading Image from the URL so the user sees
        // something materialise, and in parallel save the bytes to disk for next time.
        Image loading = new Image(card.getUrl(), true);
        MEMORY.put(card.getId(), loading);
        downloadToDiskAsync(card);
        return loading;
    }

    /// Schedules background downloads of every card in {@code cards} that isn't already on disk
    /// or in memory. Returns immediately. Use this when opening the chest so future claims/fusions
    /// reveal cards instantly.
    public static void preloadAll(List<Card> cards) {
        for (Card card : cards) {
            if (MEMORY.containsKey(card.getId())) {
                continue;
            }
            Path file = cachedFile(card.getId());
            if (Files.isRegularFile(file)) {
                continue;
            }
            downloadToDiskAsync(card);
        }
    }

    private static void downloadToDiskAsync(Card card) {
        DOWNLOADER.execute(() -> {
            Path file = cachedFile(card.getId());
            if (Files.isRegularFile(file)) {
                return; // someone beat us to it
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                try (InputStream in = LauncherUpdater.openHttps(card.getUrl());
                     OutputStream out = Files.newOutputStream(tmp)) {
                    in.transferTo(out);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        });
    }
}
