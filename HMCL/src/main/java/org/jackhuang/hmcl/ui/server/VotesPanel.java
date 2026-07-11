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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.server.BarrilmcCloud;
import org.jackhuang.hmcl.server.BarrilmcVotesService;
import org.jackhuang.hmcl.setting.Accounts;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/// Inline weekly community votes that live permanently on the launcher home (no dialog). Shows every
/// poll with live tallies and lets the signed-in user cast (or change) a single vote per poll.
@NotNullByDefault
public final class VotesPanel extends VBox {

    private final VBox pollsBox = new VBox(14);
    private final ScrollPane scroll = new ScrollPane();
    private final Label statusLabel = new Label();
    private final @Nullable String userName;

    private Timeline poller;
    private boolean voting = false;
    private String lastSignature = "";

    public VotesPanel() {
        Account account = Accounts.getSelectedAccount();
        this.userName = account == null ? null : account.getCharacter();

        getStyleClass().add("server-votes-panel");
        setSpacing(10);
        setPadding(new Insets(16));
        setFillWidth(true);

        Label heading = new Label("Votaciones semanales");
        heading.getStyleClass().add("server-news-title");

        pollsBox.setPadding(new Insets(2, 6, 2, 2));
        pollsBox.setFillWidth(true);

        scroll.setContent(pollsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefHeight(300);
        scroll.setMinHeight(180);
        scroll.getStyleClass().add("server-news-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        statusLabel.getStyleClass().add("server-progress-label");
        if (!BarrilmcCloud.isConfigured()) {
            statusLabel.setText("Las votaciones aún no están configuradas.");
        } else if (userName == null) {
            statusLabel.setText("Inicia sesión para votar (puedes ver los resultados igualmente).");
        } else {
            statusLabel.setText("Cargando votaciones…");
        }

        getChildren().setAll(heading, scroll, statusLabel);
        pollsBox.getChildren().add(placeholder("Cargando…"));

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
        poller = new Timeline(new KeyFrame(Duration.seconds(4), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    private void refresh() {
        if (!BarrilmcCloud.isConfigured()) {
            return;
        }
        final String name = userName == null ? "" : userName;
        CompletableFuture
                .supplyAsync(() -> BarrilmcVotesService.fetchPolls(name))
                .whenComplete((polls, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || polls == null) {
                        return;
                    }
                    render(polls);
                }));
    }

    private void render(List<BarrilmcVotesService.Poll> polls) {
        if (polls.isEmpty()) {
            statusLabel.setText("Todavía no hay votaciones. ¡Pronto habrá!");
            pollsBox.getChildren().setAll(placeholder("Sin votaciones activas por ahora."));
            lastSignature = "";
            return;
        }
        String signature = signatureOf(polls);
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        statusLabel.setText(userName == null
                ? "Inicia sesión para votar. Los resultados se actualizan solos."
                : "Los resultados se actualizan en directo.");
        pollsBox.getChildren().clear();
        for (BarrilmcVotesService.Poll poll : polls) {
            pollsBox.getChildren().add(pollCard(poll));
        }
    }

    private String signatureOf(List<BarrilmcVotesService.Poll> polls) {
        StringBuilder sb = new StringBuilder();
        for (BarrilmcVotesService.Poll poll : polls) {
            sb.append(poll.getId()).append(poll.isOpen()).append(poll.getMyVote());
            for (String optId : poll.getOptions().keySet()) {
                sb.append(optId).append('=').append(poll.getCount(optId)).append(';');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    private Region pollCard(BarrilmcVotesService.Poll poll) {
        Label question = new Label(poll.getQuestion());
        question.getStyleClass().add("server-news-item-title");
        question.setWrapText(true);

        VBox card = new VBox(8, question);
        card.getStyleClass().add("server-news-item");
        card.setPadding(new Insets(12));

        int total = poll.getTotalVotes();
        boolean canVote = userName != null && poll.isOpen();
        for (Map.Entry<String, String> opt : poll.getOptions().entrySet()) {
            card.getChildren().add(optionRow(poll, opt.getKey(), opt.getValue(), total, canVote));
        }

        String footer = (poll.isOpen() ? "Abierta" : "Cerrada") + " · " + total
                + (total == 1 ? " voto" : " votos");
        Label footerLabel = new Label(footer);
        footerLabel.getStyleClass().add("server-news-item-body");
        card.getChildren().add(footerLabel);
        return card;
    }

    private Region optionRow(BarrilmcVotesService.Poll poll, String optId, String label,
                             int total, boolean canVote) {
        int count = poll.getCount(optId);
        double fraction = total > 0 ? (double) count / total : 0;
        int percent = (int) Math.round(fraction * 100);
        boolean chosen = optId.equals(poll.getMyVote());

        ProgressBar bar = new ProgressBar(fraction);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(22);
        bar.getStyleClass().add("server-progress");

        Label text = new Label((chosen ? "✓ " : "") + label + "   ·   " + count + " (" + percent + "%)");
        text.setStyle("-fx-text-fill: white; -fx-font-size: 12px;"
                + (chosen ? " -fx-font-weight: bold;" : ""));
        StackPane.setAlignment(text, Pos.CENTER_LEFT);
        StackPane.setMargin(text, new Insets(0, 0, 0, 8));

        StackPane barStack = new StackPane(bar, text);
        HBox.setHgrow(barStack, Priority.ALWAYS);

        JFXButton voteButton = new JFXButton(chosen ? "Tu voto" : "Votar");
        voteButton.getStyleClass().add(chosen ? "server-primary-button" : "server-secondary-button");
        voteButton.setMinWidth(72);
        voteButton.setDisable(!canVote || voting || chosen);
        voteButton.setOnAction(e -> castVote(poll.getId(), optId));

        HBox row = new HBox(8, barStack, voteButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void castVote(String pollId, String optId) {
        if (voting || userName == null) {
            return;
        }
        voting = true;
        statusLabel.setText("Registrando tu voto…");
        final String name = userName;
        CompletableFuture
                .supplyAsync(() -> BarrilmcVotesService.vote(pollId, name, optId))
                .whenComplete((ok, throwable) -> Platform.runLater(() -> {
                    voting = false;
                    if (throwable != null || ok == null || !ok) {
                        statusLabel.setText("No se pudo registrar el voto. Reintenta.");
                        return;
                    }
                    lastSignature = ""; // force a rebuild so the new tally shows immediately
                    refresh();
                }));
    }

    private Region placeholder(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("server-progress-label");
        label.setPadding(new Insets(12, 4, 4, 4));
        return label;
    }
}
