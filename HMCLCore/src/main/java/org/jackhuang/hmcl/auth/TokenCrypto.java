/*
 * BarrilMC Launcher
 * Copyright (C) 2026 BarrilMC contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Machine-bound encryption for sensitive token strings (access tokens, refresh
/// tokens) before they get written to `accounts.json`.
///
/// Threat model: the goal is to make `accounts.json` unusable if it is **copied
/// off this machine** — leaked via cloud backup (OneDrive/Dropbox), accidental
/// folder sharing, USB theft, etc. The encryption key is derived deterministically
/// from machine-specific identifiers (OS machine ID where available, hostname,
/// user home, OS username), so the same file on a different machine cannot be
/// decrypted.
///
/// This **does not** defend against malware running as the same user on the same
/// machine — that's a different threat model that requires OS-level secure
/// storage (Windows DPAPI, macOS Keychain) and significantly more code.
///
/// Format: `enc:v1:<base64(iv || ciphertext+gcmTag)>`. Strings without the
/// `enc:` prefix are treated as legacy plaintext for backward compatibility.
public final class TokenCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static volatile SecretKey cachedKey;

    private TokenCrypto() {
    }

    /// Encrypts a token string using the machine-derived key. Returns the
    /// encrypted form (with `enc:v1:` prefix) or the plaintext unchanged if
    /// encryption fails (so we never lose the user's tokens).
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            LOG.warning("Failed to encrypt token, falling back to plaintext", e);
            return plaintext;
        }
    }

    /// Decrypts a token string. Strings without the `enc:v1:` prefix are returned
    /// unchanged (legacy plaintext). If decryption fails (key changed, file
    /// moved to another machine, corrupted), returns null so the caller knows
    /// to prompt the user to re-login.
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) return stored; // legacy plaintext
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length < IV_LENGTH + 16) return null; // too short to be valid

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warning("Failed to decrypt token (key mismatch — file possibly moved between machines)", e);
            return null;
        }
    }

    private static SecretKey key() {
        SecretKey local = cachedKey;
        if (local != null) return local;
        synchronized (TokenCrypto.class) {
            if (cachedKey == null) {
                cachedKey = deriveKey();
            }
            return cachedKey;
        }
    }

    /// Builds a 256-bit AES key by hashing several stable machine-specific
    /// identifiers together. Order:
    ///   - OS-provided machine ID (Windows MachineGuid registry / Linux
    ///     /etc/machine-id / macOS IOPlatformUUID) — the most stable identifier
    ///   - hostname
    ///   - OS username
    ///   - user home path
    ///   - OS name
    /// If the OS machine ID is unavailable, the other identifiers are usually
    /// enough — they only become unstable if the user reinstalls the OS or
    /// renames their account, in which case re-login is acceptable.
    private static SecretKey deriveKey() {
        StringBuilder material = new StringBuilder("BarrilMC|v1|");
        material.append(safeMachineId()).append('|');
        material.append(safeHostname()).append('|');
        material.append(systemProp("user.name")).append('|');
        material.append(systemProp("user.home")).append('|');
        material.append(systemProp("os.name"));

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private static String systemProp(String key) {
        String v = System.getProperty(key);
        return v == null ? "" : v;
    }

    /// Best-effort OS machine ID. Tries the OS-specific source first; falls
    /// back to empty string if none is available (which is fine — the other
    /// identifiers still produce a unique key per (user, host)).
    private static String safeMachineId() {
        String os = systemProp("os.name").toLowerCase();
        try {
            if (os.contains("linux")) {
                Path p = Paths.get("/etc/machine-id");
                if (Files.isReadable(p)) {
                    return Files.readString(p).trim();
                }
            } else if (os.contains("mac") || os.contains("darwin")) {
                // ioreg -rd1 -c IOPlatformExpertDevice  → contains IOPlatformUUID
                Process proc = new ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                        .redirectErrorStream(true).start();
                byte[] out = proc.getInputStream().readAllBytes();
                proc.waitFor();
                String text = new String(out, StandardCharsets.UTF_8);
                int idx = text.indexOf("IOPlatformUUID");
                if (idx >= 0) {
                    int q1 = text.indexOf('"', idx);
                    int q2 = text.indexOf('"', q1 + 1);
                    int q3 = text.indexOf('"', q2 + 1);
                    int q4 = text.indexOf('"', q3 + 1);
                    if (q4 > q3) return text.substring(q3 + 1, q4);
                }
            } else if (os.contains("win")) {
                // HKLM\SOFTWARE\Microsoft\Cryptography  MachineGuid
                Process proc = new ProcessBuilder("reg", "query",
                        "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                        .redirectErrorStream(true).start();
                byte[] out = proc.getInputStream().readAllBytes();
                proc.waitFor();
                String text = new String(out, StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("MachineGuid")) {
                        int idx = trimmed.lastIndexOf(' ');
                        if (idx >= 0) return trimmed.substring(idx + 1).trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("Could not read OS machine id (falling back to hostname-only key)", e);
        }
        return "";
    }
}
