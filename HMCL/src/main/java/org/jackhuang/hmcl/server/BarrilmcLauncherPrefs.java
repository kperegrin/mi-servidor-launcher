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
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Small persistent key/value store for launcher-wide preferences (music volume, etc.). Lives in
/// {@code <launcher>/launcher-prefs.json}, written atomically via a {@code .tmp} + rename.
@NotNullByDefault
public final class BarrilmcLauncherPrefs {

    private static final String FILENAME = "launcher-prefs.json";
    private static final Object LOCK = new Object();
    private static volatile JsonObject cache = null;

    private BarrilmcLauncherPrefs() {
    }

    private static Path file() {
        return ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve(FILENAME);
    }

    private static JsonObject load() {
        JsonObject hit = cache;
        if (hit != null) {
            return hit;
        }
        synchronized (LOCK) {
            if (cache != null) {
                return cache;
            }
            JsonObject parsed = new JsonObject();
            Path f = file();
            if (Files.isRegularFile(f)) {
                try {
                    JsonElement element = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
                    if (element.isJsonObject()) {
                        parsed = element.getAsJsonObject();
                    }
                } catch (Exception ignored) {
                }
            }
            cache = parsed;
            return parsed;
        }
    }

    /// Returns the persisted music volume in 0..100, or the provided default when nothing is saved.
    public static double getMusicVolume(double fallback) {
        JsonElement value = load().get("musicVolume");
        if (value != null && value.isJsonPrimitive()) {
            try {
                return Math.max(0, Math.min(100, value.getAsDouble()));
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    public static void setMusicVolume(double volume) {
        double clamped = Math.max(0, Math.min(100, volume));
        synchronized (LOCK) {
            JsonObject root = load();
            root.addProperty("musicVolume", clamped);
            persist(root);
        }
    }

    private static void persist(JsonObject root) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Non-fatal: in-memory cache still has the up-to-date value.
        }
    }
}
