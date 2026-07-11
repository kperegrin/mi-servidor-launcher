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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/// Tiny REST client for the BarrilMC Firebase Realtime Database that powers the launcher chat and
/// the weekly votes. Firebase exposes every node at {@code <db>/<path>.json} over plain HTTPS, so we
/// only need {@code GET}/{@code POST}/{@code PUT}/{@code DELETE} with JSON bodies — no SDK required.
@NotNullByDefault
public final class BarrilmcCloud {

    private BarrilmcCloud() {
    }

    /// Whether an online backend URL is configured at all.
    public static boolean isConfigured() {
        return !ServerLauncherConfig.FIREBASE_DB_URL.isBlank();
    }

    /// Builds {@code <db>/<path>.json} with the given raw (already URL-encoded) query string, or
    /// {@code null} when no backend is configured.
    private static @Nullable String endpoint(String path, @Nullable String query) {
        if (!isConfigured()) {
            return null;
        }
        String clean = path.startsWith("/") ? path.substring(1) : path;
        String url = ServerLauncherConfig.FIREBASE_DB_URL + "/" + clean + ".json";
        return query == null || query.isBlank() ? url : url + "?" + query;
    }

    /// GET a node as a parsed JSON element. Returns {@code null} on any error or when not configured.
    public static @Nullable JsonElement get(String path, @Nullable String query) {
        String url = endpoint(path, query);
        if (url == null) {
            return null;
        }
        try {
            String body = request("GET", url, null);
            if (body == null || body.isBlank() || "null".equals(body.trim())) {
                return null;
            }
            return JsonParser.parseString(body);
        } catch (Exception e) {
            return null;
        }
    }

    /// POST a JSON body (Firebase appends it under a generated push-id key). Returns the response
    /// body (containing the new key) or {@code null} on failure.
    public static @Nullable String post(String path, String jsonBody) {
        String url = endpoint(path, null);
        if (url == null) {
            return null;
        }
        try {
            return request("POST", url, jsonBody);
        } catch (Exception e) {
            return null;
        }
    }

    /// PUT a JSON body at an exact node (overwrites). Returns true on success.
    public static boolean put(String path, String jsonBody) {
        String url = endpoint(path, null);
        if (url == null) {
            return false;
        }
        try {
            request("PUT", url, jsonBody);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /// Escapes a string as a Firebase key segment is NOT needed for Minecraft names (they are
    /// alphanumeric + underscore), but query values such as {@code "$key"} must be URL-encoded.
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static @Nullable String request(String method, String url, @Nullable String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                throw new IOException("Firebase " + method + " " + code + ": " + text);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }
}
