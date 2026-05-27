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

import org.jackhuang.hmcl.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Checks and downloads launcher updates declared by launcher/version.json.
@NotNullByDefault
public final class LauncherSelfUpdateService {
    private LauncherSelfUpdateService() {
    }

    /// Fetches version.json placed next to manifest.json.
    public static LauncherVersionInfo fetchVersionInfo() throws IOException {
        String versionUrl = URI.create(ServerLauncherConfig.MANIFEST_URL).resolve("version.json").toString();
        try (InputStream input = LauncherUpdater.openHttps(versionUrl);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return LauncherVersionInfo.read(reader);
        }
    }

    /// Downloads the new launcher asset to the current launcher directory and verifies SHA-256.
    public static Path download(LauncherVersionInfo info, LauncherUpdater.ProgressListener listener) throws IOException {
        Path outputDirectory = getOutputDirectory();
        Files.createDirectories(outputDirectory);

        Path target = outputDirectory.resolve(info.getFileName()).normalize();
        Path temporary = target.resolveSibling(target.getFileName() + ".download");
        listener.update("Descargando launcher " + info.getLatest(), 0.0);

        try (InputStream input = LauncherUpdater.openHttps(info.getDownloadUrl());
             var output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 128];
            long done = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                done += read;
                listener.update("Descargando launcher " + info.getLatest() + " (" + done / 1024 + " KiB)", -1);
            }
        }

        if (!HashUtils.matchesSha256(temporary, info.getSha256())) {
            throw new IOException("SHA-256 mismatch for launcher update");
        }

        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }

        listener.update("Launcher descargado", 1.0);
        return target;
    }

    private static Path getOutputDirectory() {
        Path self = JarUtils.thisJarPath();
        if (self != null && self.getParent() != null) {
            return self.getParent();
        }
        return Path.of(System.getProperty("user.home"), "Downloads");
    }
}
