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

import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
            runTask(installTask);
            repository.refreshVersions();
            return;
        }

        if (installFabric) {
            listener.update("Actualizando Fabric " + manifest.getLoaderVersion(), 0.88);
            Version current = repository.getVersion(ServerLauncherConfig.INSTANCE_NAME);
            Task<Version> task = profile.getDependency()
                    .installLibraryAsync(manifest.getMinecraftVersion(), current, ServerLauncherConfig.LOADER, manifest.getLoaderVersion());
            runTask(task);
            Version updated = task.getResult();
            Task<?> saveTask = repository.saveAsync(updated);
            runTask(saveTask);
            repository.refreshVersions();
        }
    }

    private static void runTask(Task<?> task) throws Exception {
        TaskExecutor executor = task.executor();
        if (!executor.test()) {
            Exception ex = executor.getException();
            if (ex != null) throw ex;
            throw new Exception("Task failed: " + task.getName());
        }
    }

    private static void syncFiles(Path runDirectory, ServerManifest manifest, ProgressListener listener) throws IOException {
        int totalFiles = Math.max(manifest.getFiles().size(), 1);

        // Build the set of "protected" relative paths: everything that the new manifest declares.
        // Used by the wipe step so que un mod/textura que el cliente ya tiene con el sha correcto
        // no se borre y luego se redescargue innecesariamente.
        Set<Path> protectedFiles = new HashSet<>();
        for (ServerFileEntry file : manifest.getFiles()) {
            protectedFiles.add(HashUtils.requireSafeRelativePath(file.getPath()).normalize());
        }

        // WIPE step (optional): para actualizaciones donde el set de mods/resourcepacks cambia
        // por completo, el manifest puede listar carpetas a vaciar antes de descargar. Borra todo
        // lo que NO está declarado en files[], evitando descargas redundantes.
        for (String folderPath : manifest.getWipe()) {
            Path folder = runDirectory.resolve(HashUtils.requireSafeRelativePath(folderPath)).normalize();
            if (!Files.isDirectory(folder)) {
                continue;
            }
            listener.update("Limpiando carpeta " + folderPath, 0.05);
            try (java.util.stream.Stream<Path> stream = Files.list(folder)) {
                List<Path> children = stream.collect(java.util.stream.Collectors.toList());
                for (Path child : children) {
                    Path relative = runDirectory.relativize(child);
                    if (protectedFiles.contains(relative)) {
                        continue; // se conserva: lo declara el manifest y ya tiene el sha correcto
                    }
                    // Solo borramos archivos; subcarpetas se dejan para no romper p.ej. config/.
                    if (Files.isRegularFile(child)) {
                        Files.deleteIfExists(child);
                    }
                }
            }
        }

        // Download/verify everything FIRST. The cleanup of obsolete files runs afterwards so that a
        // failed required download never strands the user with neither the old nor the new file.
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

        // Now that the new files are in place, remove the obsolete ones listed for deletion.
        for (String deletePath : manifest.getDelete()) {
            Path relative = HashUtils.requireSafeRelativePath(deletePath);
            Files.deleteIfExists(runDirectory.resolve(relative));
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

    /// Abre una conexión HTTPS. Si la URL es de {@code raw.githubusercontent.com} y falla
    /// con un error de DNS / red (host bloqueado, sin internet a ese dominio…) reintenta
    /// automáticamente a través de jsdelivr.net (CDN que sirve los mismos archivos del repo
    /// y suele estar accesible en redes donde raw.githubusercontent.com está bloqueado).
    static InputStream openHttps(String url) throws IOException {
        try {
            return openHttpsDirect(url);
        } catch (java.net.UnknownHostException | java.net.NoRouteToHostException
                 | java.net.ConnectException netErr) {
            String fallback = jsdelivrEquivalent(url);
            if (fallback != null && !fallback.equals(url)) {
                try {
                    return openHttpsDirect(fallback);
                } catch (IOException nestedErr) {
                    // Si el fallback también revienta, lanzamos el error ORIGINAL (más útil
                    // para diagnóstico — al usuario le importa más que raw no fuera accesible
                    // que el detalle del segundo intento).
                    throw netErr;
                }
            }
            throw netErr;
        }
    }

    /// Traduce una URL de raw.githubusercontent.com a su equivalente en jsdelivr.net.
    /// Devuelve null si la URL no encaja con el patrón conocido.
    private static @org.jetbrains.annotations.Nullable String jsdelivrEquivalent(String rawUrl) {
        if (rawUrl == null) return null;
        String prefix = "https://raw.githubusercontent.com/";
        String lc = rawUrl.toLowerCase(Locale.ROOT);
        if (!lc.startsWith(prefix)) return null;
        // Conservamos el query string (cache-busts ?t=, ?v= se respetan).
        String query = "";
        int q = rawUrl.indexOf('?');
        String pathPart = rawUrl;
        if (q >= 0) {
            query = rawUrl.substring(q);
            pathPart = rawUrl.substring(0, q);
        }
        // Esperamos: raw.githubusercontent.com/<owner>/<repo>/<branch>/<path...>
        String[] parts = pathPart.substring(prefix.length()).split("/", 4);
        if (parts.length < 4) return null;
        return "https://cdn.jsdelivr.net/gh/" + parts[0] + "/" + parts[1]
                + "@" + parts[2] + "/" + parts[3] + query;
    }

    private static InputStream openHttpsDirect(String url) throws IOException {
        String lc = url.toLowerCase(Locale.ROOT);
        if (!lc.startsWith("https://") && !lc.startsWith("http://")) {
            throw new IOException("Only HTTP/HTTPS URLs are allowed: " + url);
        }
        String currentUrl = url;
        for (int redirects = 0; redirects < 10; redirects++) {
            String currentLc = currentUrl.toLowerCase(Locale.ROOT);
            if (!currentLc.startsWith("https://") && !currentLc.startsWith("http://")) {
                throw new IOException("Redirect to non-HTTP URL: " + currentUrl);
            }

            HttpURLConnection connection = (HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
            connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", ServerLauncherConfig.LAUNCHER_NAME + "/1.0");

            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect with no Location from " + currentUrl);
                }
                currentUrl = URI.create(currentUrl).resolve(location).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " while downloading " + currentUrl);
            }
            return new BufferedInputStream(connection.getInputStream());
        }
        throw new IOException("Too many redirects for " + url);
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
