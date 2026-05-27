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

import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.task.Task;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/// Synchronizes the local server instance with the remote manifest.
@NotNullByDefault
public final class LauncherUpdater {
    private static final int HTTP_TIMEOUT_MILLIS = 30000;
    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;

    private LauncherUpdater() {
    }

    /// Callback used to report update progress to the UI.
    @FunctionalInterface
    public interface ProgressListener {
        /// Reports the current user-visible update step.
        void update(String message, double progress);
    }

    /// Downloads the remote manifest and prepares the local instance.
    public static ServerManifest prepare(Profile profile, ProgressListener listener) throws Exception {
        listener.update("Leyendo manifest remoto", 0.02);
        ServerManifest manifest = fetchManifest();
        fetchStandaloneNews(manifest, listener);

        HMCLGameRepository repository = profile.getRepository();
        Path runDirectory = repository.getRunDirectory(ServerLauncherConfig.INSTANCE_NAME);
        createBaseDirectories(runDirectory);
        writeRecommendedOptions(runDirectory);
        writeServersDatIfMissing(runDirectory, manifest);

        syncFiles(runDirectory, manifest, listener);
        ensureFabricInstance(profile, manifest, listener);
        listener.update("Instancia lista", 1.0);
        return manifest;
    }

    /// Downloads and parses the configured manifest URL.
    public static ServerManifest fetchManifest() throws IOException {
        try (InputStream input = openHttps(ServerLauncherConfig.MANIFEST_URL);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return ServerManifest.read(reader);
        }
    }

    private static void fetchStandaloneNews(ServerManifest manifest, ProgressListener listener) {
        String newsUrl = URI.create(ServerLauncherConfig.MANIFEST_URL).resolve("news.json").toString();
        try (InputStream input = openHttps(newsUrl);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            List<ServerManifest.NewsEntry> news = ServerManifest.readNews(reader);
            manifest.setNews(news);
            listener.update("Noticias remotas cargadas", 0.04);
        } catch (IOException e) {
            listener.update("Usando noticias del manifest", 0.04);
        }
    }

    private static void ensureFabricInstance(Profile profile, ServerManifest manifest, ProgressListener listener) throws Exception {
        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            repository.refreshVersions();
        }

        boolean rebuild = false;
        boolean installFabric = true;
        if (repository.hasVersion(ServerLauncherConfig.INSTANCE_NAME)) {
            Version current = repository.getVersion(ServerLauncherConfig.INSTANCE_NAME);
            String installedMinecraft = repository.getGameVersion(current).orElse("");
            if (!installedMinecraft.isBlank() && !installedMinecraft.equals(manifest.getMinecraftVersion())) {
                rebuild = true;
            } else {
                Version resolved = current.resolve(repository);
                LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(resolved, manifest.getMinecraftVersion());
                installFabric = !analyzer.has(LibraryAnalyzer.LibraryType.FABRIC)
                        || analyzer.getVersion(LibraryAnalyzer.LibraryType.FABRIC)
                                .map(version -> !version.equals(manifest.getLoaderVersion()))
                                .orElse(true);
            }
        } else {
            rebuild = true;
        }

        if (rebuild) {
            if (repository.hasVersion(ServerLauncherConfig.INSTANCE_NAME)) {
                listener.update("Recreando instancia " + ServerLauncherConfig.INSTANCE_NAME, 0.82);
                repository.removeVersionFromDisk(ServerLauncherConfig.INSTANCE_NAME);
                repository.refreshVersions();
            }

            listener.update("Instalando Minecraft " + manifest.getMinecraftVersion() + " + Fabric", 0.86);
            Task<?> installTask = profile.getDependency()
                    .gameBuilder()
                    .name(ServerLauncherConfig.INSTANCE_NAME)
                    .gameVersion(manifest.getMinecraftVersion())
                    .version(ServerLauncherConfig.LOADER, manifest.getLoaderVersion())
                    .buildAsync();
            installTask.run();
            repository.refreshVersions();
            return;
        }

        if (installFabric) {
            listener.update("Actualizando Fabric " + manifest.getLoaderVersion(), 0.88);
            Version current = repository.getVersion(ServerLauncherConfig.INSTANCE_NAME);
            Task<Version> task = profile.getDependency()
                    .installLibraryAsync(manifest.getMinecraftVersion(), current, ServerLauncherConfig.LOADER, manifest.getLoaderVersion());
            Version updated = task.run();
            repository.saveAsync(updated).run();
            repository.refreshVersions();
        }
    }

    private static void syncFiles(Path runDirectory, ServerManifest manifest, ProgressListener listener) throws IOException {
        int totalFiles = Math.max(manifest.getFiles().size(), 1);

        for (String deletePath : manifest.getDelete()) {
            Path relative = HashUtils.requireSafeRelativePath(deletePath);
            Files.deleteIfExists(runDirectory.resolve(relative));
        }

        int index = 0;
        for (ServerFileEntry file : manifest.getFiles()) {
            index++;
            int currentIndex = index;
            Path target = runDirectory.resolve(HashUtils.requireSafeRelativePath(file.getPath())).normalize();
            if (isCurrent(target, file)) {
                listener.update("Archivo correcto: " + file.getPath(), 0.08 + currentIndex * 0.70 / totalFiles);
                continue;
            }

            try {
                downloadAndVerify(file, target, (done, total) -> {
                    double itemProgress = total > 0 ? Math.min(1.0, (double) done / total) : 0.5;
                    double progress = 0.08 + ((currentIndex - 1) + itemProgress) * 0.70 / totalFiles;
                    listener.update("Descargando " + file.getPath(), progress);
                });
            } catch (IOException e) {
                if (file.isRequired()) {
                    throw e;
                }
                listener.update("Opcional omitido: " + file.getPath(), 0.08 + currentIndex * 0.70 / totalFiles);
            }
        }
    }

    private static boolean isCurrent(Path target, ServerFileEntry file) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (file.getSize() >= 0 && Files.size(target) != file.getSize()) {
            return false;
        }
        return HashUtils.matchesSha256(target, file.getSha256());
    }

    private static void downloadAndVerify(ServerFileEntry file, Path target, ByteProgress progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".download");

        try (InputStream input = openHttps(file.getUrl())) {
            long total = file.getSize();
            long done = 0L;
            byte[] buffer = new byte[1024 * 128];
            try (var output = Files.newOutputStream(temporary)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    done += read;
                    progress.update(done, total);
                }
            }

            if (file.getSize() >= 0 && Files.size(temporary) != file.getSize()) {
                throw new IOException("Downloaded size mismatch for " + file.getPath());
            }
            if (!HashUtils.matchesSha256(temporary, file.getSha256())) {
                throw new IOException("SHA-256 mismatch for " + file.getPath());
            }

            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static InputStream openHttps(String url) throws IOException {
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("Only HTTPS URLs are allowed: " + url);
        }

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", ServerLauncherConfig.LAUNCHER_NAME + "/1.0");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " while downloading " + url);
        }
        return new BufferedInputStream(connection.getInputStream());
    }

    private static void createBaseDirectories(Path runDirectory) throws IOException {
        Files.createDirectories(runDirectory.resolve("mods"));
        Files.createDirectories(runDirectory.resolve("resourcepacks"));
        Files.createDirectories(runDirectory.resolve("shaderpacks"));
        Files.createDirectories(runDirectory.resolve("config"));
    }

    private static void writeRecommendedOptions(Path runDirectory) throws IOException {
        Path options = runDirectory.resolve("options.txt");
        if (Files.exists(options)) {
            return;
        }
        Files.writeString(options, String.join("\n",
                "lang:es_es",
                "tutorialStep:none",
                "enableVsync:true",
                "maxFps:120",
                "guiScale:2",
                "pauseOnLostFocus:false",
                ""), StandardCharsets.UTF_8);
    }

    private static void writeServersDatIfMissing(Path runDirectory, ServerManifest manifest) throws IOException {
        Path serversDat = runDirectory.resolve("servers.dat");
        if (Files.exists(serversDat)) {
            return;
        }
        Files.createDirectories(serversDat.getParent());
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(serversDat)))) {
            writeRootServersTag(output, manifest.getServer());
        }
    }

    private static void writeRootServersTag(DataOutputStream output, ServerManifest.ServerInfo server) throws IOException {
        output.writeByte(TAG_COMPOUND);
        writeNbtString(output, "");

        output.writeByte(TAG_LIST);
        writeNbtString(output, "servers");
        output.writeByte(TAG_COMPOUND);
        output.writeInt(1);

        writeNamedString(output, "name", server.getName());
        writeNamedString(output, "ip", server.getAddress());
        writeNamedByte(output, "acceptTextures", (byte) 0);
        output.writeByte(TAG_END);

        output.writeByte(TAG_END);
    }

    private static void writeNamedString(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(TAG_STRING);
        writeNbtString(output, name);
        writeNbtString(output, value);
    }

    private static void writeNamedByte(DataOutputStream output, String name, byte value) throws IOException {
        output.writeByte(TAG_BYTE);
        writeNbtString(output, name);
        output.writeByte(value);
    }

    private static void writeNbtString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    @FunctionalInterface
    private interface ByteProgress {
        void update(long done, long total);
    }
}
