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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Exports the latest BarrilMC YouTube video thumbnail into the game instance so the in-game
/// client mod can show it without doing any network/image-decoding work itself.
///
/// The launcher runs on a normal JVM where {@code ImageIO} and HTTPS work reliably, unlike the
/// Fabric-isolated classloader inside the running game (where decoding JPEG/PNG via STB or ImageIO
/// is unreliable and silently fails). To sidestep decoding entirely, we download + decode the JPEG
/// here and write the thumbnail as a tiny raw-RGBA blob ({@code video.rgba}) the mod can feed into
/// a {@code NativeImage} pixel-by-pixel. We also drop {@code video.txt} (watch URL + title).
///
/// {@code video.rgba} format (big-endian): magic {@code "BMR1"} (4 bytes), width (int), height
/// (int), then {@code width*height} pixels of 4 bytes each in R,G,B,A order.
@NotNullByDefault
public final class BarrilmcVideoExporter {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String[] QUALITIES = {"maxresdefault", "sddefault", "hqdefault", "mqdefault"};
    /// Magic header identifying the raw-RGBA thumbnail blob.
    private static final byte[] MAGIC = {'B', 'M', 'R', '1'};
    /// Downscale target width; keeps the blob small (640x360 ≈ 900 KB) while staying crisp.
    private static final int MAX_WIDTH = 640;

    private BarrilmcVideoExporter() {
    }

    /// Best-effort export of the latest video thumbnail. Never throws.
    public static void export(Path instanceDir) {
        try {
            List<YouTubeFeedService.VideoEntry> videos =
                    YouTubeFeedService.fetchVideosByHandle(ServerLauncherConfig.YOUTUBE_CHANNEL_HANDLE);
            if (videos.isEmpty()) {
                LOG.warning("BarrilMC video export: channel returned no videos");
                return;
            }
            YouTubeFeedService.VideoEntry latest = videos.get(0);

            BufferedImage image = downloadThumbnail(latest.getVideoId());
            if (image == null) {
                LOG.warning("BarrilMC video export: could not download a thumbnail for " + latest.getVideoId());
                return;
            }

            Path dir = instanceDir.resolve("barrilmc");
            Files.createDirectories(dir);

            byte[] blob = encodeRgba(image);

            // Write atomically so the mod never reads a half-written file.
            Path rgbaTmp = dir.resolve("video.rgba.tmp");
            try (OutputStream out = Files.newOutputStream(rgbaTmp)) {
                out.write(blob);
            }
            move(rgbaTmp, dir.resolve("video.rgba"));

            String meta = latest.getUrl() + "\n" + latest.getTitle() + "\n";
            Path txtTmp = dir.resolve("video.txt.tmp");
            Files.write(txtTmp, meta.getBytes(StandardCharsets.UTF_8));
            move(txtTmp, dir.resolve("video.txt"));

            // Remove the obsolete PNG from older launcher builds so the mod doesn't prefer it.
            Files.deleteIfExists(dir.resolve("video.png"));

            LOG.info("BarrilMC video exported (" + latest.getVideoId() + ", "
                    + image.getWidth() + "x" + image.getHeight() + " -> raw RGBA "
                    + blob.length + " bytes) to " + dir.resolve("video.rgba"));
        } catch (Throwable t) {
            LOG.warning("BarrilMC video export failed", t);
        }
    }

    /// Downscales (if needed) and serialises the image to the raw-RGBA blob format described in the
    /// class doc, so the mod can rebuild a {@code NativeImage} without any image decoding.
    private static byte[] encodeRgba(BufferedImage src) throws IOException {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int w = srcW;
        int h = srcH;
        if (srcW > MAX_WIDTH) {
            w = MAX_WIDTH;
            h = Math.max(1, (int) Math.round((double) srcH * MAX_WIDTH / srcW));
        }

        // Render into a known ARGB raster so getRGB() always yields 0xAARRGGBB regardless of the
        // source image's colour model.
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + w * h * 4);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(w);
        out.writeInt(h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb.getRGB(x, y);
                out.writeByte((p >> 16) & 0xFF); // R
                out.writeByte((p >> 8) & 0xFF);  // G
                out.writeByte(p & 0xFF);         // B
                out.writeByte((p >> 24) & 0xFF); // A
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BufferedImage downloadThumbnail(String videoId) {
        for (String quality : QUALITIES) {
            try {
                byte[] bytes = httpGet("https://i.ytimg.com/vi/" + videoId + "/" + quality + ".jpg");
                if (bytes != null && bytes.length > 1024) {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image != null) {
                        return image;
                    }
                }
            } catch (Throwable ignored) {
                // try a lower quality
            }
        }
        return null;
    }

    private static byte[] httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", "https://www.youtube.com/");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(12_000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }
}
