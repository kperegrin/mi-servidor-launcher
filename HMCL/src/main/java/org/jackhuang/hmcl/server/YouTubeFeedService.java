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

/// Fetches and parses the YouTube Atom RSS feed for a given channel.
public final class YouTubeFeedService {

    private YouTubeFeedService() {
    }

    private static final String FEED_URL =
            "https://www.youtube.com/feeds/videos.xml?channel_id=";

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

    /// Fetches the latest videos for the given channel ID from the YouTube Atom feed.
    public static List<VideoEntry> fetchVideos(String channelId) throws IOException {
        String xml = HttpRequest.GET(FEED_URL + channelId).getString();
        return parseAtomFeed(xml);
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
