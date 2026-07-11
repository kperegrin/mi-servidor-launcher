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

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Central configuration for the server-specific launcher build.
@NotNullByDefault
public final class ServerLauncherConfig {
    /// Launcher display name.
    public static final String LAUNCHER_NAME = "BarrilMC Launcher";
    /// Server display name and profile name.
    public static final String SERVER_NAME = "BarrilMC Server";
    /// Preconfigured Minecraft instance id.
    public static final String INSTANCE_NAME = "BarrilMC Server";
    /// Default Minecraft version. The remote manifest may repeat this value.
    public static final String MINECRAFT_VERSION = "1.21.1";
    /// Loader id used by HMCL's dependency manager.
    public static final String LOADER = "fabric";
    /// Default Fabric loader version. The remote manifest may repeat this value.
    public static final String LOADER_VERSION = "0.18.4";
    /// Default server host.
    public static final String SERVER_IP = "impact.dathost.net";
    /// Default server port.
    public static final int SERVER_PORT = 17818;
    /// Base directory used by the BarrilMC launcher on this PC.
    public static final Path LAUNCHER_DIRECTORY = resolveLauncherDirectory();
    /// Default local game directory for this server.
    public static final Path INSTANCE_DIRECTORY = LAUNCHER_DIRECTORY.resolve(".barrilmc");
    /// Default manifest URL. Override with -Dbarrilmc.manifest.url or BARRILMC_MANIFEST_URL.
    public static final String MANIFEST_URL = System.getProperty(
            "barrilmc.manifest.url",
            System.getenv().getOrDefault(
                    "BARRILMC_MANIFEST_URL",
                    "https://raw.githubusercontent.com/kperegrin/mi-servidor-launcher/main/launcher/manifest.json"));

    /// Firebase Realtime Database base URL backing the launcher chat + weekly votes, e.g.
    /// {@code https://barrilmc-xxxx-default-rtdb.firebaseio.com} (no trailing slash). Leave blank
    /// to disable the online features. Override with -Dbarrilmc.firebase.url or BARRILMC_FIREBASE_URL.
    public static final String FIREBASE_DB_URL = normalizeUrl(System.getProperty(
            "barrilmc.firebase.url",
            System.getenv().getOrDefault(
                    "BARRILMC_FIREBASE_URL",
                    "https://barril-chat-default-rtdb.europe-west1.firebasedatabase.app")));

    /// YouTube channel handle for @barrilmc. Used to resolve the channel ID at runtime.
    public static final String YOUTUBE_CHANNEL_HANDLE = "@barrilmc";
    /// YouTube channel base URL.
    public static final String YOUTUBE_CHANNEL_URL = "https://www.youtube.com/@barrilmc";
    /// YouTube channel community posts URL.
    public static final String YOUTUBE_POSTS_URL = "https://www.youtube.com/@barrilmc/posts";
    /// YouTube UC… channel ID. When non-empty this is used directly, skipping the fragile
    /// channel-page scrape. Find it in YouTube Studio → Configuración → Canal → Info básica.
    public static final String YOUTUBE_CHANNEL_ID = "UCJQZKTJ8gFF3NGouvxfF3XQ";

    /// URL a la que se envía al usuario cuando el launcher detecta que no tiene Java. HMCL
    /// upstream apunta a una web china (loongnix.cn) que no nos sirve. Por defecto usamos
    /// Adoptium (Eclipse Temurin) — la build open-source más confiable de OpenJDK 21. Cuando
    /// tengas tu landing en Vercel cambia este valor por la suya.
    public static final String JAVA_DOWNLOAD_URL =
            "https://adoptium.net/temurin/releases/?version=21";

    /// EULA que se enseña en el primer arranque. Por defecto el oficial de Minecraft.
    public static final String EULA_URL = "https://www.minecraft.net/es-es/eula";

    private ServerLauncherConfig() {
    }

    /// Trims and strips a trailing slash so callers can append {@code /path.json} safely.
    private static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static Path resolveLauncherDirectory() {
        String override = System.getProperty("barrilmc.launcher.dir", System.getenv("BARRILMC_LAUNCHER_DIR"));
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        // User-chosen path stored in the bootstrap config (set via "Mover instalacion" button).
        Path custom = BarrilmcInstallConfig.getCustomInstallPath();
        if (custom != null) {
            return custom;
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "BarrilMCLauncher").toAbsolutePath().normalize();
        }

        return Path.of(System.getProperty("user.home"), "BarrilMCLauncher").toAbsolutePath().normalize();
    }
}
