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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Bootstrap config stored at a fixed system location, separate from the game data directory,
/// so that the launcher can find its data even after it has been moved to another drive.
///
/// Config file lives at: %LOCALAPPDATA%\BarrilMCLauncher\install.json
@NotNullByDefault
public final class BarrilmcInstallConfig {

    private static final String KEY_INSTALL_PATH = "installPath";

    private BarrilmcInstallConfig() {}

    static Path configFile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData, "BarrilMCLauncher")
                : Path.of(System.getProperty("user.home"), ".barrilmc");
        return base.resolve("install.json");
    }

    /// Returns the user-chosen install path, or null to use the default.
    public static @Nullable Path getCustomInstallPath() {
        Path cfg = configFile();
        if (!Files.isRegularFile(cfg)) return null;
        try {
            JsonElement el = JsonParser.parseString(Files.readString(cfg, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonElement pathEl = el.getAsJsonObject().get(KEY_INSTALL_PATH);
            if (pathEl == null || !pathEl.isJsonPrimitive()) return null;
            String s = pathEl.getAsString().trim();
            if (s.isBlank()) return null;
            return Path.of(s).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    /// Persists the chosen install path to the bootstrap config.
    public static void setCustomInstallPath(Path path) throws IOException {
        Path cfg = configFile();
        Files.createDirectories(cfg.getParent());
        JsonObject root = new JsonObject();
        root.addProperty(KEY_INSTALL_PATH, path.toAbsolutePath().normalize().toString());
        Path tmp = cfg.resolveSibling("install.json.tmp");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING);
    }

    @FunctionalInterface
    public interface MoveProgress {
        void update(long done, long total, String currentFile);
    }

    /// Copies all files from src to dst, then saves the new install path.
    /// The old directory is NOT deleted to avoid accidental data loss — the user
    /// can remove it manually after verifying the new location works correctly.
    public static void moveInstallation(Path src, Path dst, MoveProgress progress) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(src)) {
            files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        }

        long total = files.size();
        long done = 0;
        for (Path file : files) {
            Path relative = src.relativize(file);
            Path target = dst.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            progress.update(++done, total, relative.toString());
        }

        setCustomInstallPath(dst);
    }
}
