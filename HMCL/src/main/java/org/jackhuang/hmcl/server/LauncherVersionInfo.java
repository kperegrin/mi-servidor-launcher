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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.Locale;

/// Remote launcher version declaration read from launcher/version.json.
@NotNullByDefault
public final class LauncherVersionInfo {
    private String latest = "1.0.0";
    private String downloadUrl = "";
    private String sha256 = "";
    private String notes = "";

    /// Reads and validates version.json.
    public static LauncherVersionInfo read(Reader reader) throws IOException {
        LauncherVersionInfo info = JsonUtils.GSON.fromJson(reader, LauncherVersionInfo.class);
        if (info == null) {
            throw new IOException("version.json is empty");
        }
        info.normalize();
        info.validate();
        return info;
    }

    private void normalize() {
        if (latest == null || latest.isBlank()) {
            latest = "1.0.0";
        }
        if (downloadUrl == null) {
            downloadUrl = "";
        }
        if (sha256 == null) {
            sha256 = "";
        }
        if (notes == null) {
            notes = "";
        }
    }

    private void validate() throws IOException {
        if (!downloadUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("Launcher download URL must be HTTPS");
        }
        if (!sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Launcher SHA-256 must be 64 hexadecimal characters");
        }
    }

    /// Returns true if version.json declares a launcher newer than the running build.
    public boolean isNewerThanCurrent() {
        return VersionNumber.compare(Metadata.VERSION, latest) < 0;
    }

    /// Latest launcher version.
    public String getLatest() {
        return latest;
    }

    /// Direct GitHub Releases download URL.
    public String getDownloadUrl() {
        return downloadUrl;
    }

    /// Expected SHA-256 for the launcher asset.
    public String getSha256() {
        return sha256;
    }

    /// Release notes.
    public String getNotes() {
        return notes;
    }

    /// File name extracted from the release URL.
    public String getFileName() {
        String path = URI.create(downloadUrl).getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? ServerLauncherConfig.LAUNCHER_NAME.replace(' ', '-') + "-" + latest + ".jar" : name;
    }
}
