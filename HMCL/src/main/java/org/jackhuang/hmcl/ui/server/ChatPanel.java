/*
 * BarrilMC Launcher
 * Copyright (C) 2026 BarrilMC contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.ui.server;

import com.jfoenix.controls.JFXButton;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.server.BarrilmcChatService;
import org.jackhuang.hmcl.server.BarrilmcCloud;
import org.jackhuang.hmcl.setting.Accounts;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/// Inline live chat that lives permanently on the launcher home (no dialog). Polls Firebase every
/// couple of seconds while it is attached to the scene; the author name is the selected Minecraft
/// account, and each line is prefixed with that player's Minecraft head, like in-game.
@NotNullByDefault
public final class ChatPanel extends VBox {

    /// Player-head avatars cached per username so we only download each one once.
    private static final Map<String, Image> HEAD_CACHE = new ConcurrentHashMap<>();
    private static final int AVATAR_SIZE = 20;

    private final VBox messagesBox = new VBox(8);
    private final ScrollPane scroll = new ScrollPane();
    private final TextField input = new TextField();
    private final JFXButton sendButton = new JFXButton("Enviar");
    private final Label statusLabel = new Label();
    private final @Nullable String userName;

    private Timeline poller;
    private @Nullable String lastRenderedId = null;
    private boolean sending = false;

    public ChatPanel() {
        Account account = Accounts.getSelectedAccount();
        this.userName = account == null ? null : account.getCharacter();

        getStyleClass().add("server-chat-panel");
        setSpacing(10);
        setPadding(new Insets(16));
        setFillWidth(true);

        // --- Header: title + live dot ---
        Label heading = new Label("Chat de la comunidad");
        heading.getStyleClass().add("server-news-title");
        Circle dot = new Circle(4);
        dot.getStyleClass().add("server-live-dot");
        Label live = new Label("EN VIVO");
        live.getStyleClass().add("server-live-label");
        HBox liveBox = new HBox(5, dot, live);
        liveBox.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, heading, spacer, liveBox);
        header.setAlignment(Pos.CENTER_LEFT);

        messagesBox.setPadding(new Insets(2, 6, 2, 2));
        messagesBox.setFillWidth(true);

        scroll.setContent(messagesBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefHeight(220);
        scroll.setMinHeight(160);
        scroll.getStyleClass().add("server-news-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        input.setPromptText(userName == null
                ? "Inicia sesión para escribir…"
                : "Escribe un mensaje y pulsa Enter…");
        input.getStyleClass().add("server-chat-input");
        input.setDisable(userName == null);
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> doSend());

        sendButton.getStyleClass().addAll("server-primary-button", "dialog-accept");
        sendButton.setStyle("-fx-text-fill: white;");
        sendButton.setDisable(userName == null);
        sendButton.setOnAction(e -> doSend());

        HBox inputRow = new HBox(8, input, sendButton);
        inputRow.setAlignment(Pos.CENTER);

        statusLabel.getStyleClass().add("server-progress-label");
        statusLabel.setText(BarrilmcCloud.isConfigured()
                ? "Conectando…"
                : "El chat aún no está configurado.");

        getChildren().setAll(header, scroll, inputRow, statusLabel);
        messagesBox.getChildren().add(placeholder("Cargando mensajes…"));

        // Poll only while attached to a live scene; stop as soon as the home leaves the view.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                stopPolling();
            } else {
                startPolling();
            }
        });
    }

    private void startPolling() {
        refresh();
        stopPolling();
        poller = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    private void doSend() {
        if (sending || userName == null) {
            return;
        }
        String text = input.getText();
        if (text == null || text.strip().isEmpty()) {
            return;
        }
        sending = true;
        input.clear();
        final String name = userName;
        final String message = text;
        CompletableFuture
                .supplyAsync(() -> BarrilmcChatService.send(name, message))
                .whenComplete((ok, throwable) -> Platform.runLater(() -> {
                    sending = false;
                    if (throwable != null || ok == null || !ok) {
                        statusLabel.setText("No se pudo enviar el mensaje. Reintenta.");
                        input.setText(message);
                    } else {
                        statusLabel.setText("");
                        refresh();
                    }
                }));
    }

    private void refresh() {
        if (!BarrilmcCloud.isConfigured()) {
            return;
        }
        CompletableFuture
                .supplyAsync(BarrilmcChatService::fetchRecent)
                .whenComplete((messages, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || messages == null) {
                        if (lastRenderedId == null) {
                            statusLabel.setText("Sin conexión con el chat. Reintentando…");
                        }
                        return;
                    }
                    render(messages);
                }));
    }

    private void render(List<BarrilmcChatService.ChatMessage> messages) {
        if (messages.isEmpty()) {
            if (lastRenderedId != null) {
                return;
            }
            messagesBox.getChildren().setAll(placeholder("Sé el primero en escribir 👋"));
            return;
        }
        String newest = messages.get(messages.size() - 1).getId();
        if (newest.equals(lastRenderedId)) {
            return; // nothing changed; avoid flicker + scroll jumps
        }
        lastRenderedId = newest;
        statusLabel.setText("");
        messagesBox.getChildren().clear();
        for (BarrilmcChatService.ChatMessage m : messages) {
            messagesBox.getChildren().add(messageNode(m));
        }
        Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    private Region messageNode(BarrilmcChatService.ChatMessage m) {
        boolean mine = userName != null && userName.equals(m.getName());

        Text name = new Text(m.getName() + " ");
        name.setStyle("-fx-font-weight: bold; -fx-fill: " + (mine ? "#ffaa22" : "#7fb0ff") + ";");
        Text text = new Text(m.getText());
        text.setStyle("-fx-fill: rgba(235,240,255,0.92);");
        TextFlow flow = new TextFlow(name, text);
        flow.setMaxWidth(440);
        HBox.setHgrow(flow, Priority.ALWAYS);

        HBox row = new HBox(8, avatar(m.getName()), flow);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("server-chat-row");
        return row;
    }

    /// Minecraft head for {@code name}, downloaded once and cached. Rendered crisp (no smoothing)
    /// with slightly rounded corners so it reads like the in-game player head.
    private Region avatar(String name) {
        ImageView view = new ImageView();
        view.setFitWidth(AVATAR_SIZE);
        view.setFitHeight(AVATAR_SIZE);
        view.setSmooth(false);
        Rectangle clip = new Rectangle(AVATAR_SIZE, AVATAR_SIZE);
        clip.setArcWidth(6);
        clip.setArcHeight(6);
        view.setClip(clip);

        Image cached = HEAD_CACHE.get(name);
        if (cached != null) {
            view.setImage(cached);
        } else {
            String url = "https://minotar.net/helm/" + encodeName(name) + "/" + AVATAR_SIZE + ".png";
            Image image = new Image(url, AVATAR_SIZE, AVATAR_SIZE, true, false, true);
            HEAD_CACHE.put(name, image);
            view.setImage(image);
        }

        StackPane holder = new StackPane(view);
        holder.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        holder.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        holder.getStyleClass().add("server-chat-avatar");
        return holder;
    }

    private static String encodeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "MHF_Steve" : sb.toString();
    }

    private Region placeholder(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("server-progress-label");
        label.setPadding(new Insets(12, 4, 4, 4));
        return label;
    }
}
