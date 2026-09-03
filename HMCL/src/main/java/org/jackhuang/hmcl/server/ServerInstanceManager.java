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

            // Scale heap to total RAM: Cobblemon + CobbleTCG packs are very heavy.
            long totalMB = (long) MEGABYTES.convertFromBytes(SystemInfo.getTotalMemorySize());
            int heapMB = totalMB >= 32768 ? 10240   // 32 GB → 10 GB
                       : totalMB >= 16384 ? 7168    // 16 GB → 7 GB
                       : totalMB >= 8192  ? 4096    // 8 GB  → 4 GB
                       : 2048;                       // <8 GB → 2 GB
            setting.setMaxMemory(heapMB);
            setting.setAutoMemory(false); // fixed value; no surprises from low available-RAM at launch

            // Cap Metaspace so 100+ mods loading many classes don't OOM outside the heap.
            // MaxDirectMemorySize is intentionally omitted: watermedia/waterframes video buffers
            // can exceed 1 GB at startup and capping it causes a hang on the Mojang screen.
            setting.setJavaArgs("-XX:MaxMetaspaceSize=512m");

            repository.saveVersionSetting(ServerLauncherConfig.INSTANCE_NAME);
        }

        profile.setSelectedVersion(ServerLauncherConfig.INSTANCE_NAME);
        Profiles.setSelectedProfile(profile);
    }
}
