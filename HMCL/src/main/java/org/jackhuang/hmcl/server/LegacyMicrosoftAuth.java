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

import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;

import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Pair.pair;

/// Legacy MSA OAuth flow used by the official Minecraft Java launcher.
///
/// This flow uses the public client ID `00000000402b5328` together with the
/// `https://login.live.com/oauth20_desktop.srf` redirect URI — a "post to
/// blank page" target Microsoft hosts for desktop apps that can't host an
/// HTTP callback. The user logs in via the system browser, lands on a blank
/// page whose URL bar contains `?code=...`, then pastes that URL back into
/// the launcher.
///
/// The scope `service::user.auth.xboxlive.com::MBI_SSL` returns an RPS
/// ticket directly, so the subsequent Xbox Live call must use the `t=`
/// RpsTicket prefix instead of the `d=` prefix used by modern OAuth.
public final class LegacyMicrosoftAuth {

    public static final String CLIENT_ID = "00000000402b5328";
    public static final String REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    public static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private static final String AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    private LegacyMicrosoftAuth() {
    }

    /// Builds the full authorize URL the user opens in their browser.
    /// Includes the same `lw/fl/xsup/nopa` parameters the official launcher
    /// uses to suppress the "approve this app" consent screens.
    public static String buildAuthorizeUrl() {
        // LinkedHashMap to keep parameter order stable (matches official launcher).
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", CLIENT_ID);
        params.put("response_type", "code");
        params.put("scope", SCOPE);
        params.put("redirect_uri", REDIRECT_URI);
        params.put("prompt", "select_account");
        // Mimic the official Minecraft launcher to skip extra consent screens.
        params.put("lw", "1");
        params.put("fl", "dob,easi2");
        params.put("xsup", "1");
        params.put("nopa", "2");
        return NetworkUtils.withQuery(AUTHORIZE_URL, params);
    }

    /// Extracts the `code` parameter from either:
    ///   - a full pasted URL (`https://login.live.com/oauth20_desktop.srf?code=...`)
    ///   - or a bare code string the user copied out of the URL bar.
    /// Returns null if no code can be recovered.
    public static String extractCode(String pastedInput) {
        if (pastedInput == null) return null;
        String input = pastedInput.trim();
        if (input.isEmpty()) return null;

        // If it doesn't look like a URL, treat it as a raw code.
        if (!input.contains("://") && !input.contains("?") && !input.contains("=")) {
            return input;
        }

        // Try to parse as URL and look for code= in query.
        try {
            String query;
            // Some browsers leak the code in the fragment; handle both.
            if (input.startsWith("http://") || input.startsWith("https://")) {
                URL parsed = new URL(input);
                query = parsed.getQuery();
                if (query == null && parsed.getRef() != null) {
                    query = parsed.getRef();
                }
            } else {
                query = input;
            }
            if (query == null) return null;

            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq);
                if ("code".equals(key)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /// Exchanges an auth code for an access + refresh token at oauth20_token.srf.
    public static TokenResponse exchangeCodeForToken(String code) throws IOException {
        return HttpRequest.POST(TOKEN_URL)
                .form(mapOf(
                        pair("client_id", CLIENT_ID),
                        pair("code", code),
                        pair("grant_type", "authorization_code"),
                        pair("redirect_uri", REDIRECT_URI),
                        pair("scope", SCOPE)
                ))
                .accept("application/json")
                .getJson(TokenResponse.class);
    }

    /// Refreshes an expired access token.
    public static TokenResponse refresh(String refreshToken) throws IOException {
        return HttpRequest.POST(TOKEN_URL)
                .form(mapOf(
                        pair("client_id", CLIENT_ID),
                        pair("refresh_token", refreshToken),
                        pair("grant_type", "refresh_token"),
                        pair("redirect_uri", REDIRECT_URI),
                        pair("scope", SCOPE)
                ))
                .accept("application/json")
                .getJson(TokenResponse.class);
    }

    /// Response from the legacy MSA token endpoint.
    public static final class TokenResponse {
        @SerializedName("access_token")
        public String accessToken;

        @SerializedName("refresh_token")
        public String refreshToken;

        @SerializedName("expires_in")
        public int expiresIn;

        @SerializedName("token_type")
        public String tokenType;

        @SerializedName("scope")
        public String scope;

        @SerializedName("user_id")
        public String userId;

        @SerializedName("error")
        public String error;

        @SerializedName("error_description")
        public String errorDescription;
    }
}
