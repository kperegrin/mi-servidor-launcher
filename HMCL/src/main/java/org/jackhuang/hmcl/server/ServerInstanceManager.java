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

import org.jackhuang.hmcl.game.GameDirectoryType;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.VersionIconType;
import org.jackhuang.hmcl.setting.VersionSetting;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Optional;

/// Creates and configures the dedicated MiServidor profile and instance.
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
    public static void applyLaunchSettings(Profile profile, ServerManifest manifest) {
        FXUtils.checkFxUserThread();

        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            repository.refreshVersions();
        }

        VersionSetting setting = repository.specializeVersionSetting(ServerLauncherConfig.INSTANCE_NAME);
        if (setting != null) {
            setting.setUsesGlobal(false);
            setting.setGameDirType(GameDirectoryType.ROOT_FOLDER);
            setting.setServerIp(manifest.getServer().getAddress());
            setting.setVersionIcon(VersionIconType.FABRIC);
            repository.saveVersionSetting(ServerLauncherConfig.INSTANCE_NAME);
        }

        profile.setSelectedVersion(ServerLauncherConfig.INSTANCE_NAME);
        Profiles.setSelectedProfile(profile);
    }
}
