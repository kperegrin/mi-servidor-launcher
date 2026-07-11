/*
 * BarrilMC Launcher
 * Copyright (C) 2026 BarrilMC contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.mod.ModManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Ensures the bundled BarrilMC integrated client mod is present in a version's {@code mods}
 * folder before every launch. If the user deletes it, it is restored on the next start, which —
 * together with {@link ModManager#BARRILMC_CLIENT_FILE} being hidden from the mod manager — makes
 * the client effectively non-removable while still being configurable in-game.
 *
 * <p>The mod is a Fabric mod compiled against Minecraft 1.21.1, so it is only injected into Fabric
 * 1.21.1 instances. Any other instance (different loader or game version) is left untouched to
 * avoid crashing the game.</p>
 */
public final class BarrilmcClientInjector {

    /** Classpath location of the bundled mod jar (see {@code assets/barrilmc/barrilmc-client.jar}). */
    private static final String BUNDLED_RESOURCE = "/assets/barrilmc/barrilmc-client.jar";

    /** Only this exact game version is compatible with the bundled mod. */
    private static final String SUPPORTED_GAME_VERSION = "1.21.1";

    /**
     * Legacy add-on jars that earlier launcher versions injected (the bundled Feather remake). These
     * are no longer shipped, so we actively delete any leftover copy on every launch to undo the old
     * injection — otherwise the user keeps loading the obsolete (and laggy) Feather menu.
     */
    private static final String[] LEGACY_FILES = {
            "barrilmc-extras.jar",
            "barrilmc-extras.jar.disabled",
            "feather-remake.jar",
            "feather-remake.jar.disabled",
            "feather_remake-13.1.jar",
            "feather_remake-13.1.jar.disabled",
    };

    private BarrilmcClientInjector() {
    }

    /**
     * Best-effort injection. Never throws: any failure is logged and the launch proceeds without
     * the client rather than blocking the user from playing.
     */
    public static void ensureInjected(GameRepository repository, String versionId, Version version, String gameVersion) {
        try {
            if (version == null) {
                return;
            }
            if (!SUPPORTED_GAME_VERSION.equals(gameVersion)) {
                return; // Mod is built for 1.21.1 only.
            }

            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(version, gameVersion);
            if (!analyzer.has(LibraryAnalyzer.LibraryType.FABRIC)) {
                return; // Fabric-only mod.
            }

            byte[] bundled = readBundledJar(BUNDLED_RESOURCE);
            if (bundled == null) {
                return;
            }

            Path modsDirectory = repository.getModsDirectory(versionId);
            Files.createDirectories(modsDirectory);

            // Remove obsolete add-on jars that older launcher builds injected.
            cleanupLegacyMods(modsDirectory);

            Path target = modsDirectory.resolve(ModManager.BARRILMC_CLIENT_FILE);

            // Re-enable a copy the user may have tried to disable.
            Path disabled = modsDirectory.resolve(ModManager.BARRILMC_CLIENT_FILE + ".disabled");
            if (Files.exists(disabled)) {
                Files.deleteIfExists(disabled);
            }

            if (isUpToDate(target, bundled)) {
                return;
            }

            Files.write(target, bundled); // CREATE + WRITE + TRUNCATE_EXISTING by default
            LOG.info("Injected BarrilMC client into " + target);
        } catch (Throwable e) {
            LOG.warning("Failed to inject BarrilMC client mod", e);
        }
    }

    /** Deletes any leftover legacy add-on jars (best-effort, per launch). */
    private static void cleanupLegacyMods(Path modsDirectory) {
        for (String name : LEGACY_FILES) {
            try {
                Path legacy = modsDirectory.resolve(name);
                if (Files.deleteIfExists(legacy)) {
                    LOG.info("Removed obsolete BarrilMC add-on " + legacy);
                }
            } catch (IOException e) {
                LOG.warning("Failed to remove obsolete BarrilMC add-on " + name, e);
            }
        }
    }

    private static boolean isUpToDate(Path target, byte[] bundled) {
        try {
            if (!Files.exists(target) || Files.size(target) != bundled.length) {
                return false;
            }
            return Arrays.equals(Files.readAllBytes(target), bundled);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readBundledJar(String resource) {
        try (InputStream in = BarrilmcClientInjector.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warning("Bundled BarrilMC jar not found at " + resource);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            LOG.warning("Failed to read bundled BarrilMC jar " + resource, e);
            return null;
        }
    }
}
