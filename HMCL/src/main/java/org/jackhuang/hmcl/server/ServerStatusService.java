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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/// Queries a Minecraft server using the official Server List Ping protocol.
@NotNullByDefault
public final class ServerStatusService {
    private static final int PROTOCOL_1_20_1 = 763;
    private static final int TIMEOUT_MILLIS = 2500;

    /// Queries the server status asynchronously.
    public CompletableFuture<Status> queryAsync(String host, int port) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return query(host, port);
            } catch (Exception e) {
                return Status.offline();
            }
        });
    }

    private Status query(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            writePacket(output, createHandshake(host, port));
            writePacket(output, new byte[]{0x00});
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = readVarInt(input);
            byte[] packet = input.readNBytes(length);
            DataInputStream packetInput = new DataInputStream(new ByteArrayInputStream(packet));
            int packetId = readVarInt(packetInput);
            if (packetId != 0x00) {
                throw new IOException("Unexpected status packet id: " + packetId);
            }

            String json = readString(packetInput);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            JsonObject players = object.has("players") ? object.getAsJsonObject("players") : new JsonObject();
            JsonObject version = object.has("version") ? object.getAsJsonObject("version") : new JsonObject();

            int online = players.has("online") ? players.get("online").getAsInt() : 0;
            int max = players.has("max") ? players.get("max").getAsInt() : 0;
            String versionName = version.has("name") ? version.get("name").getAsString() : "";
            return Status.online(online, max, versionName);
        }
    }

    private static byte[] createHandshake(String host, int port) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeVarInt(output, 0x00);
        writeVarInt(output, PROTOCOL_1_20_1);
        writeString(output, host);
        output.writeShort(port);
        writeVarInt(output, 1);
        return bytes.toByteArray();
    }

    private static void writePacket(DataOutputStream output, byte[] payload) throws IOException {
        writeVarInt(output, payload.length);
        output.write(payload);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        byte[] bytes = input.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            output.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        int numRead = 0;
        byte read;
        do {
            read = input.readByte();
            int value = read & 0x7F;
            result |= value << (7 * numRead);
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt is too large");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    /// Immutable status snapshot.
    public record Status(boolean online, int onlinePlayers, int maxPlayers, @Nullable String version) {
        /// Creates an online status.
        public static Status online(int onlinePlayers, int maxPlayers, String version) {
            return new Status(true, onlinePlayers, maxPlayers, version);
        }

        /// Creates an offline status.
        public static Status offline() {
            return new Status(false, 0, 0, null);
        }
    }
}
