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

import org.jackhuang.hmcl.util.io.HttpRequest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Fetches and parses the YouTube Atom RSS feed for a given channel.
public final class YouTubeFeedService {

    private YouTubeFeedService() {
    }

    private static final String FEED_URL =
            "https://www.youtube.com/feeds/videos.xml?channel_id=";

    /// Regex to extract a UC… channel ID from a YouTube page's embedded JSON or HTML.
    /// Matches channelId, externalId, browseId keys, and /channel/UC… URL patterns.
    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile(
            "\"(?:channelId|externalId|browseId)\"\\s*:\\s*\"(UC[\\w-]{22})\"|" +
            "/channel/(UC[\\w-]{22})");

    /// A single video entry from the YouTube RSS feed.
    public static final class VideoEntry {
        private final String videoId;
        private final String title;
        private final String published;

        public VideoEntry(String videoId, String title, String published) {
            this.videoId = videoId;
            this.title = title;
            this.published = published;
        }

        public String getVideoId() {
            return videoId;
        }

        public String getTitle() {
            return title;
        }

        /// Full YouTube watch URL for this video.
        public String getUrl() {
            return "https://www.youtube.com/watch?v=" + videoId;
        }

        /// Medium-quality thumbnail URL (320 × 180 px).
        public String getThumbnailUrl() {
            return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
        }

        /// Date formatted as "d MMM yyyy", e.g. "3 Jan 2025".
        public String getFormattedDate() {
            try {
                OffsetDateTime dt = OffsetDateTime.parse(published, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return dt.format(DateTimeFormatter.ofPattern("d MMM yyyy"));
            } catch (DateTimeParseException e) {
                return published.length() >= 10 ? published.substring(0, 10) : published;
            }
        }
    }

    /// Fetches the latest videos using a channel handle like "@barrilmc".
    /// If YOUTUBE_CHANNEL_ID is set in ServerLauncherConfig, uses it directly to avoid
    /// scraping the channel page (which breaks when YouTube changes its HTML structure).
    /// Otherwise resolves the handle to a channel ID by scraping.
    public static List<VideoEntry> fetchVideosByHandle(String handle) throws IOException {
        String channelId = ServerLauncherConfig.YOUTUBE_CHANNEL_ID;
        if (channelId == null || channelId.isBlank()) {
            channelId = resolveChannelId(handle);
        }
        return fetchVideos(channelId);
    }

    /// Fetches the latest videos for the given channel ID from the YouTube Atom feed.
    public static List<VideoEntry> fetchVideos(String channelId) throws IOException {
        String xml = HttpRequest.GET(FEED_URL + channelId).getString();
        return parseAtomFeed(xml);
    }

    /// Resolves a YouTube handle (e.g. "@barrilmc") to a UC… channel ID.
    private static String resolveChannelId(String handle) throws IOException {
        String url = "https://www.youtube.com/" + handle;
        String html = HttpRequest.GET(url)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                // SOCS=CAE= bypasses YouTube's consent/cookie banner
                .header("Cookie", "SOCS=CAE=; CONSENT=YES+cb.20210328-17-p0.en+FX+")
                .getString();

        Matcher m = CHANNEL_ID_PATTERN.matcher(html);
        while (m.find()) {
            // Pattern has two capture groups (JSON key and URL pattern); one will be null
            String id = m.group(1) != null ? m.group(1) : m.group(2);
            if (id != null) return id;
        }
        throw new IOException("Could not resolve channel ID from YouTube handle: " + handle
                + " (consent page may have been served — set YOUTUBE_CHANNEL_ID in ServerLauncherConfig)");
    }

    private static List<VideoEntry> parseAtomFeed(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            List<VideoEntry> result = new ArrayList<>();
            NodeList entries = doc.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String videoId = getText(entry, "yt:videoId");
                String title = getText(entry, "title");
                String published = getText(entry, "published");
                if (!videoId.isEmpty() && !title.isEmpty()) {
                    result.add(new VideoEntry(videoId, title, published));
                }
            }
            return result;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse YouTube RSS feed", e);
        }
    }

    private static String getText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}
