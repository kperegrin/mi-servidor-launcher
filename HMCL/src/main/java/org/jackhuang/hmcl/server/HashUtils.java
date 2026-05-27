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

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// Hashing and path-safety helpers used by the server updater.
@NotNullByDefault
public final class HashUtils {
    private HashUtils() {
    }

    /// Calculates the SHA-256 digest of a local file.
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[1024 * 128];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Returns true when the file exists and matches the expected SHA-256.
    public static boolean matchesSha256(Path file, String expectedSha256) throws IOException {
        return Files.isRegularFile(file) && sha256(file).equalsIgnoreCase(expectedSha256);
    }

    /// Converts a manifest path to a safe relative path.
    public static Path requireSafeRelativePath(String manifestPath) throws IOException {
        if (manifestPath == null || manifestPath.isBlank()) {
            throw new IOException("Manifest path is empty");
        }
        if (manifestPath.indexOf('\0') >= 0) {
            throw new IOException("Manifest path contains a NUL byte: " + manifestPath);
        }

        Path relative = Path.of(manifestPath.replace('\\', '/')).normalize();
        if (relative.isAbsolute()) {
            throw new IOException("Manifest path escapes the instance directory: " + manifestPath);
        }
        for (Path part : relative) {
            if ("..".equals(part.toString())) {
                throw new IOException("Manifest path escapes the instance directory: " + manifestPath);
            }
        }
        return relative;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java platform", e);
        }
    }
}
