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
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.server.CardCollectionService;
import org.jackhuang.hmcl.server.CardCollectionService.Card;
import org.jackhuang.hmcl.server.CardCollectionService.Inventory;
import org.jackhuang.hmcl.server.CardImageCache;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;

/// "Pokédex" view: shows every card in the catalogue. Cards the player has ever owned (whether
/// they still have them or fused them away) appear in full colour. Cards they've never seen show
/// as a desaturated silhouette with the name hidden as {@code "???"}, leaving the rarity-coloured
/// border visible as a hint about how rare it is to roll.
@NotNullByDefault
public final class PokedexDialog extends JFXDialogLayout {

    public PokedexDialog(String playerName) {
        getStyleClass().add("microsoft-login-dialog");
        setMaxWidth(820);
        setPrefWidth(800);

        // Las cartas secret (incluidas las INFINITO) NO aparecen en la Pokédex aunque no las
        // tengas: la idea es que sean un easter egg que solo se descubre al tocarlas.
        List<Card> catalogue = new java.util.ArrayList<>();
        for (Card c : CardCollectionService.listAllCards()) {
            if (!c.isSecret()) catalogue.add(c);
        }
        Inventory inv = CardCollectionService.loadInventory(playerName);
        Set<String> seen = inv.getSeen();

        int total = catalogue.size();
        int discovered = 0;
        for (Card card : catalogue) {
            if (seen.contains(card.getId())) {
                discovered++;
            }
        }

        Label heading = new Label("Pokédex   ·   " + discovered + " / " + total + " descubiertas");
        heading.getStyleClass().add("header-label");
        setHeading(heading);

        FlowPane grid = new FlowPane(14, 14);
        grid.setPadding(new Insets(6));
        grid.setAlignment(Pos.TOP_LEFT);

        for (Card card : catalogue) {
            grid.getChildren().add(buildTile(card, seen.contains(card.getId())));
        }

        if (catalogue.isEmpty()) {
            Label empty = new Label("El catalogo esta vacio.");
            empty.getStyleClass().add("server-progress-label");
            empty.setPadding(new Insets(24, 8, 24, 8));
            grid.getChildren().add(empty);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefHeight(520);
        scroll.setMinHeight(360);
        scroll.getStyleClass().add("server-news-scroll");

        Label hint = new Label("Las cartas en gris no las has conseguido todavia. ¡Sigue reclamando o fusionando para descubrirlas!");
        hint.getStyleClass().add("server-progress-label");
        hint.setWrapText(true);

        VBox body = new VBox(10, scroll, hint);
        body.setPrefHeight(580);
        setBody(body);

        JFXButton close = new JFXButton("Cerrar");
        close.getStyleClass().add("dialog-cancel");
        close.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        setActions(close);
        onEscPressed(this, () -> fireEvent(new DialogCloseEvent()));
    }

    private Region buildTile(Card card, boolean discovered) {
        double w = 144;
        double h = 200;

        ImageView art = new ImageView();
        try {
            art.setImage(CardImageCache.getImage(card));
        } catch (RuntimeException ignored) {
        }
        art.setFitWidth(w - 16);
        art.setFitHeight(h - 64);
        art.setPreserveRatio(true);
        art.setSmooth(true);

        if (!discovered) {
            // Silhouette: drop the colour and dim it noticeably. The rarity border still leaks
            // through so the player can guess "ah, this slot is for a Mítica" without seeing the art.
            ColorAdjust gray = new ColorAdjust();
            gray.setSaturation(-1.0);
            gray.setBrightness(-0.55);
            gray.setContrast(-0.20);
            art.setEffect(gray);
        }

        String displayName = discovered ? card.getName() : "???";
        Label name = new Label(displayName);
        name.getStyleClass().add("server-card-name");
        if (!discovered) {
            name.setStyle("-fx-text-fill: rgba(180,180,180,0.55); -fx-font-style: italic;");
        }
        name.setWrapText(true);
        name.setMaxWidth(w - 16);
        name.setAlignment(Pos.CENTER);

        Label rarity = new Label(card.getRarity().getDisplayName());
        rarity.setStyle("-fx-text-fill: " + card.getRarity().getColor() + ";"
                + " -fx-font-size: 10px; -fx-font-weight: bold;"
                + (discovered ? "" : " -fx-opacity: 0.65;"));

        VBox content = new VBox(4, art, name, rarity);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(8));

        StackPane tile = new StackPane(content);
        tile.setMinSize(w, h);
        tile.setPrefSize(w, h);
        tile.setMaxSize(w, h);
        tile.getStyleClass().add("server-card-tile");
        // Discovered tiles get a slightly stronger border; locked ones a faded one — the colour
        // is still the rarity tint so the player can plan which to chase.
        String borderOpacity = discovered ? "1.0" : "0.45";
        tile.setStyle("-fx-border-color: " + card.getRarity().getColor() + ";"
                + " -fx-border-width: 2; -fx-border-radius: 10; -fx-background-radius: 10;"
                + " -fx-background-color: rgba(255,255,255," + (discovered ? "0.05" : "0.025") + ");"
                + " -fx-opacity: " + borderOpacity + ";");
        return tile;
    }
}
