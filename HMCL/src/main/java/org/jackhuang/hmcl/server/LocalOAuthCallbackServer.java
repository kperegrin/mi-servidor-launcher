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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/// Minimal HTTP server that listens on a random free port and captures the
/// OAuth authorization code that Microsoft redirects to
/// {@code http://127.0.0.1:PORT/callback?code=...}.
///
/// Usage:
/// <pre>
///   try (LocalOAuthCallbackServer srv = new LocalOAuthCallbackServer()) {
///       String authUrl = LegacyMicrosoftAuth.buildAuthorizeUrlWithRedirect(srv.getRedirectUri());
///       FXUtils.openLink(authUrl);
///       String code = srv.getCodeFuture().get(5, TimeUnit.MINUTES);
///       // exchange code for token ...
///   }
/// </pre>
public final class LocalOAuthCallbackServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final int port;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
    private volatile boolean closed = false;

    public LocalOAuthCallbackServer() throws IOException {
        // Port 0 lets the OS pick any free port.
        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
        // Timeout after 5 minutes if the user never completes the browser flow.
        serverSocket.setSoTimeout(300_000);

        Thread listener = new Thread(this::listenForCallback, "oauth-callback-listener");
        listener.setDaemon(true);
        listener.start();
    }

    /// The redirect URI to embed in the OAuth authorize URL.
    public String getRedirectUri() {
        return "http://127.0.0.1:" + port + "/callback";
    }

    /// Completes with the auth code when the browser redirects, or completes
    /// exceptionally on timeout / error.
    public CompletableFuture<String> getCodeFuture() {
        return codeFuture;
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        codeFuture.cancel(true);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void listenForCallback() {
        try {
            // We only need to accept ONE connection (the browser redirect).
            Socket client = serverSocket.accept();
            handleClient(client);
        } catch (IOException e) {
            if (!closed && !codeFuture.isDone()) {
                codeFuture.completeExceptionally(
                        new IOException("Tiempo de espera agotado. Por favor, inténtalo de nuevo.", e));
            }
        } finally {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleClient(Socket client) {
        String code = null;
        String error = null;

        try (client) {
            // Read only the first line of the HTTP request (GET /callback?... HTTP/1.1)
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = client.getInputStream().read()) != -1) {
                char c = (char) b;
                if (c == '\n') break;
                sb.append(c);
            }

            String line = sb.toString().trim(); // "GET /callback?code=XXX HTTP/1.1"
            if (line.startsWith("GET ")) {
                int end = line.lastIndexOf(' ');
                String path = line.substring(4, end > 4 ? end : line.length());
                int q = path.indexOf('?');
                if (q >= 0) {
                    for (String param : path.substring(q + 1).split("&")) {
                        int eq = param.indexOf('=');
                        if (eq > 0) {
                            String key = param.substring(0, eq);
                            String value = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                            if ("code".equals(key))  code  = value;
                            else if ("error".equals(key)) error = value;
                        }
                    }
                }
            }

            // Send a branded HTML response that auto-closes the tab.
            boolean success = code != null;
            byte[] html = buildPage(success).getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + html.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().write(html);
            client.getOutputStream().flush();
        } catch (IOException ignored) {
            // Response write failure is non-critical; we already have the code.
        }

        if (code != null) {
            codeFuture.complete(code);
        } else {
            codeFuture.completeExceptionally(
                    new IOException("Microsoft devolvió un error: " + (error != null ? error : "desconocido")));
        }
    }

    private static String buildPage(boolean success) {
        String icon  = success ? "✅" : "❌";
        String title = success ? "¡Sesión iniciada!" : "Error de autenticación";
        String body  = success
                ? "Puedes cerrar esta pestaña y volver al launcher."
                : "Cierra esta pestaña e inténtalo de nuevo en el launcher.";
        String border = success ? "rgba(255,140,0,0.40)" : "rgba(220,50,50,0.40)";
        String color  = success ? "#ffaa22" : "#e05555";

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>BarrilMC Launcher</title>"
                + "<style>*{margin:0;padding:0;box-sizing:border-box;}"
                + "body{background:#04060e;color:#fff;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;}"
                + ".card{background:rgba(7,11,22,0.95);border:1px solid " + border + ";"
                + "border-radius:18px;padding:44px 56px;text-align:center;"
                + "box-shadow:0 16px 48px rgba(0,0,0,0.6);}"
                + ".icon{font-size:52px;margin-bottom:18px;}"
                + "h1{color:" + color + ";font-size:22px;font-weight:700;margin-bottom:10px;}"
                + "p{color:rgba(215,225,255,0.70);font-size:14px;line-height:1.5;}"
                + "</style>"
                + (success ? "<script>setTimeout(function(){window.close();},2500);</script>" : "")
                + "</head><body>"
                + "<div class='card'>"
                + "<div class='icon'>" + icon + "</div>"
                + "<h1>" + title + "</h1>"
                + "<p>" + body + "</p>"
                + "</div></body></html>";
    }
}
