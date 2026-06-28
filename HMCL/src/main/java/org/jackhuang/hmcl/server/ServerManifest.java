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

import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Root object of the remote server update manifest.
@NotNullByDefault
public final class ServerManifest {
    private static final Type NEWS_LIST_TYPE = new TypeToken<List<NewsEntry>>() {
    }.getType();

    private String launcherVersion = "1.0.0";
    private String minecraftVersion = ServerLauncherConfig.MINECRAFT_VERSION;
    private String loader = ServerLauncherConfig.LOADER;
    private String loaderVersion = ServerLauncherConfig.LOADER_VERSION;
    private ServerInfo server = new ServerInfo();
    private List<ServerFileEntry> files = new ArrayList<>();
    private List<String> delete = new ArrayList<>();
    private List<String> wipe = new ArrayList<>();
    private List<NewsEntry> news = new ArrayList<>();

    /// Reads and normalizes a manifest from JSON.
    public static ServerManifest read(Reader reader) throws IOException {
        ServerManifest manifest = JsonUtils.GSON.fromJson(reader, ServerManifest.class);
        if (manifest == null) {
            throw new IOException("Manifest JSON is empty");
        }
        manifest.normalize();
        manifest.validate();
        return manifest;
    }

    /// Reads a standalone news.json file.
    public static List<NewsEntry> readNews(Reader reader) throws IOException {
        List<NewsEntry> result = JsonUtils.GSON.fromJson(reader, NEWS_LIST_TYPE);
        if (result == null) {
            throw new IOException("News JSON is empty");
        }
        return result;
    }

    private void normalize() {
        if (launcherVersion == null || launcherVersion.isBlank()) {
            launcherVersion = "1.0.0";
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            minecraftVersion = ServerLauncherConfig.MINECRAFT_VERSION;
        }
        if (loader == null || loader.isBlank()) {
            loader = ServerLauncherConfig.LOADER;
        }
        if (loaderVersion == null || loaderVersion.isBlank()) {
            loaderVersion = ServerLauncherConfig.LOADER_VERSION;
        }
        if (server == null) {
            server = new ServerInfo();
        }
        server.normalize();
        if (files == null) {
            files = new ArrayList<>();
        }
        if (delete == null) {
            delete = new ArrayList<>();
        }
        if (wipe == null) {
            wipe = new ArrayList<>();
        }
        if (news == null) {
            news = new ArrayList<>();
        }
    }

    private void validate() throws IOException {
        if (!ServerLauncherConfig.LOADER.equalsIgnoreCase(loader)) {
            throw new IOException("Unsupported loader in manifest: " + loader);
        }
        for (ServerFileEntry file : files) {
            String error = file.validate();
            if (error != null) {
                throw new IOException(error);
            }
        }
    }

    /// Launcher version declared by the remote manifest.
    public String getLauncherVersion() {
        return launcherVersion;
    }

    /// Minecraft version required by the server.
    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    /// Loader id declared by the server.
    public String getLoader() {
        return loader;
    }

    /// Fabric loader version required by the server.
    public String getLoaderVersion() {
        return loaderVersion;
    }

    /// Server connection data.
    public ServerInfo getServer() {
        return server;
    }

    /// Files to synchronize into the instance.
    public List<ServerFileEntry> getFiles() {
        return Collections.unmodifiableList(files);
    }

    /// Relative paths to delete from the instance.
    public List<String> getDelete() {
        return Collections.unmodifiableList(delete);
    }

    /// Folders whose contents are wiped before downloading new files. Files declared in
    /// {@link #getFiles()} are preserved if they still match (no redundant download).
    public List<String> getWipe() {
        return Collections.unmodifiableList(wipe);
    }

    /// News shown on the custom home screen.
    public List<NewsEntry> getNews() {
        return Collections.unmodifiableList(news);
    }

    /// Replaces embedded manifest news with standalone news.json entries.
    public void setNews(List<NewsEntry> news) {
        this.news = news == null ? new ArrayList<>() : new ArrayList<>(news);
    }

    /// Server connection data in the manifest.
    public static final class ServerInfo {
        private String name = ServerLauncherConfig.SERVER_NAME;
        private String ip = ServerLauncherConfig.SERVER_IP;
        private int port = ServerLauncherConfig.SERVER_PORT;

        private void normalize() {
            if (name == null || name.isBlank()) {
                name = ServerLauncherConfig.SERVER_NAME;
            }
            if (ip == null || ip.isBlank()) {
                ip = ServerLauncherConfig.SERVER_IP;
            }
            if (port <= 0 || port > 65535) {
                port = ServerLauncherConfig.SERVER_PORT;
            }
        }

        /// Server display name.
        public String getName() {
            return name;
        }

        /// Server host or IP address.
        public String getIp() {
            return ip;
        }

        /// TCP port.
        public int getPort() {
            return port;
        }

        /// Host:port string for Minecraft launch arguments and servers.dat.
        public String getAddress() {
            return port == 25565 ? ip : ip + ":" + port;
        }
    }

    /// One news item shown by the launcher home.
    public static final class NewsEntry {
        private String title = "";
        private String body = "";
        private String date = "";

        /// News title.
        public String getTitle() {
            return title == null ? "" : title;
        }

        /// News body.
        public String getBody() {
            return body == null ? "" : body;
        }

        /// Display date.
        public String getDate() {
            return date == null ? "" : date;
        }
    }
}
