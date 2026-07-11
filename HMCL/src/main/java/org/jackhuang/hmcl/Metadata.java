/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Stores metadata about this application.
 */
public final class Metadata {
    private Metadata() {
    }

    public static final String NAME = "BarrilMC Launcher";
    public static final String FULL_NAME = "BarrilMC Launcher";
    public static final String VERSION = System.getProperty("hmcl.version.override", JarUtils.getAttribute("hmcl.version", "@develop@"));

    public static final String TITLE = NAME + " " + VERSION;
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    public static final int MINIMUM_REQUIRED_JAVA_VERSION = 17;
    public static final int MINIMUM_SUPPORTED_JAVA_VERSION = 17;
    public static final int RECOMMENDED_JAVA_VERSION = 21;

    public static final String PUBLISH_URL = "https://kperegrin.github.io/mi-servidor-launcher";
    public static final String DOWNLOAD_URL = "https://github.com/kperegrin/mi-servidor-launcher/releases";
    public static final String HMCL_UPDATE_URL = System.getProperty("hmcl.update_source.override", PUBLISH_URL + "/api/update_link");
    public static final String MANUAL_UPDATE_URL = "https://github.com/kperegrin/mi-servidor-launcher/releases";

    public static final String DOCS_URL = "https://docs.hmcl.net";
    public static final String CONTACT_URL = DOCS_URL + "/help.html";
    public static final String CHANGELOG_URL = DOCS_URL + "/changelog/";
    // BarrilMC: el acuerdo de primer arranque enseña el EULA oficial de Minecraft, no el de HMCL.
    public static final String EULA_URL = org.jackhuang.hmcl.server.ServerLauncherConfig.EULA_URL;
    public static final String GROUPS_URL = "https://www.bilibili.com/opus/905435541874409529";

    public static final String BUILD_CHANNEL = JarUtils.getAttribute("hmcl.version.type", "nightly");
    public static final String GITHUB_SHA = JarUtils.getAttribute("hmcl.version.hash", null);

    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    public static final Path MINECRAFT_DIRECTORY = OperatingSystem.getWorkingDirectory("minecraft");
    public static final Path HMCL_GLOBAL_DIRECTORY;
    public static final Path HMCL_CURRENT_DIRECTORY;
    public static final Path DEPENDENCIES_DIRECTORY;

    static {
        String hmclHome = System.getProperty("hmcl.home", System.getenv("HMCL_USER_HOME"));
        if (StringUtils.isBlank(hmclHome)) {
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                String xdgData = System.getenv("XDG_DATA_HOME");
                if (StringUtils.isNotBlank(xdgData)) {
                    HMCL_GLOBAL_DIRECTORY = Path.of(xdgData, "barrilmc-launcher").toAbsolutePath().normalize();
                } else {
                    HMCL_GLOBAL_DIRECTORY = Path.of(System.getProperty("user.home"), ".local", "share", "barrilmc-launcher").toAbsolutePath().normalize();
                }
            } else {
                HMCL_GLOBAL_DIRECTORY = OperatingSystem.getWorkingDirectory("barrilmc-launcher");
            }
        } else {
            HMCL_GLOBAL_DIRECTORY = Path.of(hmclHome).toAbsolutePath().normalize();
        }

        String hmclCurrentDir = System.getProperty("hmcl.dir", System.getenv("HMCL_LOCAL_HOME"));
        HMCL_CURRENT_DIRECTORY = StringUtils.isNotBlank(hmclCurrentDir)
                ? Path.of(hmclCurrentDir).toAbsolutePath().normalize()
                : getBarrilMCLauncherDirectory().resolve(".barrilmc").resolve("launcher");

        String hmclDependencies = System.getProperty("hmcl.dependencies.dir", System.getenv("HMCL_DEPENDENCIES_DIR"));
        DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(hmclDependencies)
                ? Path.of(hmclDependencies).toAbsolutePath().normalize()
                : HMCL_CURRENT_DIRECTORY.resolve("dependencies");
    }

    public static boolean isStable() {
        return "stable".equals(BUILD_CHANNEL);
    }

    private static Path getBarrilMCLauncherDirectory() {
        String override = System.getProperty("barrilmc.launcher.dir", System.getenv("BARRILMC_LAUNCHER_DIR"));
        if (StringUtils.isNotBlank(override)) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String appData = System.getenv("APPDATA");
        if (StringUtils.isNotBlank(appData)) {
            return Path.of(appData, "BarrilMCLauncher").toAbsolutePath().normalize();
        }

        return Path.of(System.getProperty("user.home"), "BarrilMCLauncher").toAbsolutePath().normalize();
    }

    public static boolean isDev() {
        return "dev".equals(BUILD_CHANNEL);
    }

    public static boolean isNightly() {
        return !isStable() && !isDev();
    }

    public static @Nullable String getSuggestedJavaDownloadLink() {
        // BarrilMC: ignoramos las webs de HMCL (en chino o irrelevantes) y enviamos a la URL
        // configurada en ServerLauncherConfig.JAVA_DOWNLOAD_URL. Por defecto Adoptium.
        return org.jackhuang.hmcl.server.ServerLauncherConfig.JAVA_DOWNLOAD_URL;
    }
}
