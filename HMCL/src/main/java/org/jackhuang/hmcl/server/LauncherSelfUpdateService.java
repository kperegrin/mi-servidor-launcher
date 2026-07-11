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
        versionUrl = addCacheBust(versionUrl);
        try (InputStream input = LauncherUpdater.openHttps(versionUrl)) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
                json = json.substring(1);
            }
            return LauncherVersionInfo.read(json);
        }
    }

    /// Downloads the new launcher asset to a temporary exe in the current launcher directory and verifies SHA-256.
    public static Path download(LauncherVersionInfo info, LauncherUpdater.ProgressListener listener) throws IOException {
        Path outputDirectory = getOutputDirectory();
        Files.createDirectories(outputDirectory);

        Path temporary = Files.createTempFile(outputDirectory, "BarrilMC-Launcher-" + info.getLatest() + "-", ".exe");
        listener.update("Descargando launcher " + info.getLatest(), 0.0);

        try {
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
            listener.update("Launcher descargado", 1.0);
            return temporary;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    /// Starts the downloaded launcher. The UI decides when to close the current process.
    public static void startDownloadedLauncher(Path launcherPath) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(launcherPath.toAbsolutePath().toString());
        Path parent = launcherPath.getParent();
        if (parent != null) {
            builder.directory(parent.toFile());
        }
        builder.start();
    }

    /// Replaces the currently running launcher with the downloaded one, then starts the old path.
    ///
    /// Windows keeps the running exe locked, so the actual replacement must happen from a small
    /// helper process after this JVM exits. This preserves taskbar pins and shortcuts because the
    /// original file path stays the launcher entry point.
    public static void replaceCurrentLauncherAndRestart(Path downloadedLauncher) throws IOException {
        Path currentLauncher = JarUtils.thisJarPath();
        if (currentLauncher == null || !Files.isRegularFile(currentLauncher)) {
            startDownloadedLauncher(downloadedLauncher);
            return;
        }

        currentLauncher = currentLauncher.toAbsolutePath().normalize();
        downloadedLauncher = downloadedLauncher.toAbsolutePath().normalize();
        if (currentLauncher.equals(downloadedLauncher)) {
            startDownloadedLauncher(downloadedLauncher);
            return;
        }

        if (isWindows()) {
            startWindowsReplacementScript(currentLauncher, downloadedLauncher);
        } else {
            Files.move(downloadedLauncher, currentLauncher, StandardCopyOption.REPLACE_EXISTING);
            startDownloadedLauncher(currentLauncher);
        }
    }

    private static String addCacheBust(String url) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "t=" + System.currentTimeMillis();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void startWindowsReplacementScript(Path currentLauncher, Path downloadedLauncher) throws IOException {
        Path script = Files.createTempFile("barrilmc-launcher-update-", ".ps1");
        Path workingDirectory = currentLauncher.getParent() != null
                ? currentLauncher.getParent()
                : Path.of(System.getProperty("user.home"));

        String content = ""
                + "$ErrorActionPreference = 'Stop'\n"
                + "$pidToWait = " + ProcessHandle.current().pid() + "\n"
                + "$current = '" + ps(currentLauncher.toString()) + "'\n"
                + "$downloaded = '" + ps(downloadedLauncher.toString()) + "'\n"
                + "$working = '" + ps(workingDirectory.toString()) + "'\n"
                + "try { Wait-Process -Id $pidToWait -Timeout 60 -ErrorAction SilentlyContinue } catch {}\n"
                + "Start-Sleep -Milliseconds 700\n"
                + "$done = $false\n"
                + "for ($i = 0; $i -lt 80; $i++) {\n"
                + "  try {\n"
                + "    if (Test-Path -LiteralPath $current) { Remove-Item -LiteralPath $current -Force }\n"
                + "    Move-Item -LiteralPath $downloaded -Destination $current -Force\n"
                + "    $done = $true\n"
                + "    break\n"
                + "  } catch {\n"
                + "    Start-Sleep -Milliseconds 500\n"
                + "  }\n"
                + "}\n"
                + "if ($done) {\n"
                + "  Start-Process -FilePath $current -WorkingDirectory $working\n"
                + "} else {\n"
                + "  Start-Process -FilePath $downloaded -WorkingDirectory $working\n"
                + "}\n"
                + "Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue\n";
        Files.writeString(script, content, StandardCharsets.UTF_8);

        new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-File", script.toString())
                .start();
    }

    private static String ps(String value) {
        return value.replace("'", "''");
    }

    private static Path getOutputDirectory() {
        Path self = JarUtils.thisJarPath();
        if (self != null && self.getParent() != null) {
            return self.getParent();
        }
        return Path.of(System.getProperty("user.home"), "Downloads");
    }
}
