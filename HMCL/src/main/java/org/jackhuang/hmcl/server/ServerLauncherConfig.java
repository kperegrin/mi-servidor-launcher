/*
 * MiServidor Launcher
 * Copyright (C) 2026 MiServidor contributors
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
    public static final String LAUNCHER_NAME = "MiServidor Launcher";
    /// Server display name and profile name.
    public static final String SERVER_NAME = "MiServidor";
    /// Preconfigured Minecraft instance id.
    public static final String INSTANCE_NAME = "MiServidor";
    /// Default Minecraft version. The remote manifest may repeat this value.
    public static final String MINECRAFT_VERSION = "1.21.1";
    /// Loader id used by HMCL's dependency manager.
    public static final String LOADER = "fabric";
    /// Default Fabric loader version. The remote manifest may repeat this value.
    public static final String LOADER_VERSION = "0.18.4";
    /// Default server host.
    public static final String SERVER_IP = "play.miservidor.com";
    /// Default server port.
    public static final int SERVER_PORT = 25565;
    /// Default local game directory for this server.
    public static final Path INSTANCE_DIRECTORY = Path.of(".miservidor");
    /// Default manifest URL. Override with -Dmiservidor.manifest.url or MISERVIDOR_MANIFEST_URL.
    public static final String MANIFEST_URL = System.getProperty(
            "miservidor.manifest.url",
            System.getenv().getOrDefault(
                    "MISERVIDOR_MANIFEST_URL",
                    "https://TU_USUARIO.github.io/mi-servidor-launcher/launcher/manifest.json"));

    private ServerLauncherConfig() {
    }
}
