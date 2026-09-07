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

import org.jackhuang.hmcl.game.GameDirectoryType;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.LauncherVisibility;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.VersionIconType;
import org.jackhuang.hmcl.setting.VersionSetting;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.platform.SystemInfo;

import static org.jackhuang.hmcl.util.DataSizeUnit.MEGABYTES;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/// Creates and configures the dedicated BarrilMC profile and instance.
@NotNullByDefault
public final class ServerInstanceManager {
    private ServerInstanceManager() {
    }

    /// Returns the server profile, creating it when this is the first launch.
    public static Profile getOrCreateServerProfile() {
        FXUtils.checkFxUserThread();

        Optional<Profile> existing = Profiles.getProfiles().stream()
                .filter(profile -> ServerLauncherConfig.SERVER_NAME.equals(profile.getName()))
                .findFirst();

        Profile profile = existing.orElseGet(() -> {
            Profile created = new Profile(
                    ServerLauncherConfig.SERVER_NAME,
                    ServerLauncherConfig.INSTANCE_DIRECTORY,
                    new VersionSetting(),
                    ServerLauncherConfig.INSTANCE_NAME,
                    true);
            Profiles.getProfiles().add(created);
            return created;
        });

        Path targetDirectory = ServerLauncherConfig.INSTANCE_DIRECTORY;
        if (!profile.getGameDir().normalize().equals(targetDirectory.normalize())) {
            profile.setGameDir(targetDirectory);
        }

        Profiles.setSelectedProfile(profile);
        return profile;
    }

    /// Applies launch settings that must run on the JavaFX thread before launching.
    /// Equivalent to {@code applyLaunchSettings(profile, manifest, true)} (auto-joins the server).
    public static void applyLaunchSettings(Profile profile, ServerManifest manifest) {
        applyLaunchSettings(profile, manifest, true);
    }

    /// Applies launch settings that must run on the JavaFX thread before launching.
    ///
    /// @param quickJoin when {@code true} the game auto-connects to the server (quick play);
    ///                  when {@code false} the {@code serverIp} is cleared so the game opens on
    ///                  the main menu instead of joining directly.
    public static void applyLaunchSettings(Profile profile, ServerManifest manifest, boolean quickJoin) {
        FXUtils.checkFxUserThread();

        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            repository.refreshVersions();
        }

        VersionSetting setting = repository.specializeVersionSetting(ServerLauncherConfig.INSTANCE_NAME);
        if (setting != null) {
            setting.setUsesGlobal(false);
            setting.setGameDirType(GameDirectoryType.ROOT_FOLDER);
            // Quick-play target: the server address joins directly; empty opens the main menu.
            setting.setServerIp(quickJoin ? manifest.getServer().getAddress() : "");
            setting.setVersionIcon(VersionIconType.FABRIC);
            setting.setLauncherVisibility(LauncherVisibility.HIDE_AND_REOPEN);

            // Seed a heap only on first launch, while the setting is still untouched. After that
            // the player's own value wins: this method runs on every launch and must not overwrite
            // what they picked in the settings screen.
            if (setting.isAutoMemory()) {
                long totalMB = (long) MEGABYTES.convertFromBytes(SystemInfo.getTotalMemorySize());
                setting.setMaxMemory(totalMB >= 16384 ? 6144 : 4096);
                setting.setAutoMemory(false);
            }

            setting.setJavaArgs(withMemoryLimits(setting.getJavaArgs(), setting.getMaxMemory()));

            repository.saveVersionSetting(ServerLauncherConfig.INSTANCE_NAME);
        }

        profile.setSelectedVersion(ServerLauncherConfig.INSTANCE_NAME);
        Profiles.setSelectedProfile(profile);
    }

    private static final Pattern MANAGED_MEMORY_FLAGS =
            Pattern.compile("\\s*-XX:(?:MaxMetaspaceSize|MaxDirectMemorySize)=\\S+");

    /// Bounds the memory regions that live outside the heap, so the process stays near the
    /// player's `-Xmx` instead of several times it. Direct memory otherwise defaults to the heap
    /// size (the video mods fill it) and Metaspace has no ceiling at all.
    ///
    /// Both limits are deliberately generous: a 512m Metaspace cap is too small for 100+ mods and
    /// hangs class loading on the Mojang screen. Previously written values are stripped first, so
    /// re-running this on every launch neither duplicates nor drifts, and any other argument the
    /// player added by hand is preserved.
    private static String withMemoryLimits(String javaArgs, int heapMB) {
        String custom = MANAGED_MEMORY_FLAGS.matcher(javaArgs != null ? javaArgs : "").replaceAll("").trim();
        String managed = "-XX:MaxMetaspaceSize=1536m -XX:MaxDirectMemorySize=" + Math.max(1024, heapMB / 2) + "m";
        return custom.isEmpty() ? managed : managed + " " + custom;
    }
}
