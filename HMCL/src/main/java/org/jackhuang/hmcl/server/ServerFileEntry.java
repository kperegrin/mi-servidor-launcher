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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One file declared by the server manifest.
@NotNullByDefault
public final class ServerFileEntry {
    private String path = "";
    private String url = "";
    private String sha256 = "";
    private long size = -1L;
    private boolean required = true;

    /// Relative path inside the configured game directory.
    public String getPath() {
        return path;
    }

    /// Direct HTTPS URL used to download the file.
    public String getUrl() {
        return url;
    }

    /// Expected SHA-256 hex digest.
    public String getSha256() {
        return sha256;
    }

    /// Expected size in bytes, or -1 when the manifest does not pin it.
    public long getSize() {
        return size;
    }

    /// Whether this file is mandatory for joining the server.
    public boolean isRequired() {
        return required;
    }

    /// Returns a validation error, or null when the entry can be used.
    public @Nullable String validate() {
        if (path == null || path.isBlank()) {
            return "File entry has an empty path";
        }
        if (url == null || url.isBlank()) {
            return "File entry " + path + " has an empty URL";
        }
        if (!url.startsWith("https://")) {
            return "File entry " + path + " must use HTTPS";
        }
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            return "File entry " + path + " has an invalid SHA-256";
        }
        return null;
    }
}
