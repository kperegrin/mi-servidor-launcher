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
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.jackhuang.hmcl.server.CardCollectionService.Card;
import org.jackhuang.hmcl.server.CardCollectionService.Rarity;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/// Full-screen card-opening animation. A glowing chest pulses for a second and then explodes into a
/// shower of particles, after which the awarded card floats in with a continuous halo. Higher
/// rarities ramp up the particle count, the chest pulse and add a screen-shake.
@NotNullByDefault
public final class CardRevealOverlay extends StackPane {

    private static final Random RANDOM = new Random();

    /// Builds and immediately plays the reveal animation as a modal dialog.
    public static void play(Card card, String headerText) {
        play(card, headerText, null);
    }

    /// Builds and immediately plays the reveal animation as a modal dialog.
    public static void play(Card card, String headerText, @Nullable Runnable onClose) {
        CardRevealOverlay overlay = new CardRevealOverlay(card, headerText);
        overlay.setOnClose(onClose);
        Controllers.dialog(overlay);
        overlay.start();
    }

    private final Card card;
    private final String headerText;
    private final Color rarityColor;
    private final Pane particleLayer = new Pane();
    private final StackPane chestNode;
    private StackPane lidNode;
    private final VBox cardNode;
    private final Label headerLabel;
    private final JFXButton closeButton;
    private @Nullable Runnable onClose;

    private CardRevealOverlay(Card card, String headerText) {
        this.card = card;
        this.headerText = headerText;
        this.rarityColor = Color.web(card.getRarity().getColor());

        // ---- Overlay backdrop ----
        setMinSize(720, 520);
        setPrefSize(720, 520);
        setMaxSize(720, 520);
        setStyle("-fx-background-color: rgba(0,0,0,0.92); -fx-background-radius: 12;"
                + " -fx-border-radius: 12;"
                + " -fx-border-width: 2; -fx-border-color: " + card.getRarity().getColor() + ";");

        // Soft radial glow tinted by the rarity behind everything else.
        Rectangle bgGlow = new Rectangle(720, 520);
        bgGlow.setArcWidth(20); bgGlow.setArcHeight(20);
        bgGlow.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.7, true, CycleMethod.NO_CYCLE,
                new Stop(0, brighter(rarityColor, 0.35)),
                new Stop(1, Color.TRANSPARENT)));
        bgGlow.setMouseTransparent(true);

        particleLayer.setMouseTransparent(true);
        particleLayer.setPrefSize(720, 520);

        // ---- Chest (closed at first, then opens and releases the card) ----
        chestNode = buildChest();

        // ---- Card (hidden until reveal) ----
        cardNode = buildCardView();
        cardNode.setOpacity(0);
        cardNode.setScaleX(0.16);
        cardNode.setScaleY(0.16);
        cardNode.setTranslateY(112);
        cardNode.setRotate(-7);

        // ---- Header + close ----
        headerLabel = new Label(headerText);
        headerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;"
                + " -fx-effect: dropshadow(gaussian, " + card.getRarity().getColor() + ", 14, 0.5, 0, 0);");
        StackPane.setAlignment(headerLabel, Pos.TOP_CENTER);
        StackPane.setMargin(headerLabel, new Insets(20, 0, 0, 0));

        closeButton = new JFXButton("Continuar");
        closeButton.getStyleClass().addAll("server-primary-button", "dialog-accept");
        closeButton.setStyle("-fx-text-fill: white;");
        closeButton.setOnAction(e -> {
            Runnable callback = onClose;
            if (callback != null) {
                callback.run();
            }
            fireEvent(new DialogCloseEvent());
        });
        closeButton.setOpacity(0);
        closeButton.setDisable(true);
        StackPane.setAlignment(closeButton, Pos.BOTTOM_CENTER);
        StackPane.setMargin(closeButton, new Insets(0, 0, 22, 0));

        getChildren().addAll(bgGlow, particleLayer, chestNode, cardNode, headerLabel, closeButton);
    }

    private void setOnClose(@Nullable Runnable onClose) {
        this.onClose = onClose;
    }

    // ============================================================================================
    //                                          Pieces
    // ============================================================================================

    private StackPane buildChest() {
        double w = 160, h = 120;

        // Dark interior, visible once the lid opens.
        Rectangle interior = new Rectangle(w - 20, h * 0.36);
        interior.setArcWidth(10); interior.setArcHeight(10);
        interior.setFill(Color.web("#08060c"));
        interior.setStroke(brighter(rarityColor, 0.35));
        interior.setStrokeWidth(2);
        interior.setTranslateY(-h * 0.03);

        // Body (gradient brown-ish, darker on bottom).
        Rectangle body = new Rectangle(w, h * 0.6);
        body.setArcWidth(14); body.setArcHeight(14);
        body.setFill(Color.web("#3a2a18"));
        body.setStroke(rarityColor);
        body.setStrokeWidth(3);
        body.setTranslateY(h * 0.14);

        // Lid (slightly taller, sits on top with a tiny offset).
        Rectangle lid = new Rectangle(w, h * 0.45);
        lid.setArcWidth(14); lid.setArcHeight(14);
        lid.setFill(Color.web("#4a3520"));
        lid.setStroke(rarityColor);
        lid.setStrokeWidth(3);

        // Lock plate.
        Rectangle lock = new Rectangle(28, 24);
        lock.setArcWidth(6); lock.setArcHeight(6);
        lock.setFill(rarityColor);
        lock.setStroke(Color.web("#101016"));
        lock.setStrokeWidth(2);
        lock.setTranslateY(h * 0.18);

        // Tiny gold trim along the lid seam (just for personality).
        Rectangle trim = new Rectangle(w - 12, 4);
        trim.setArcWidth(2); trim.setArcHeight(2);
        trim.setFill(brighter(rarityColor, 0.55));
        trim.setTranslateY(h * 0.17);

        lidNode = new StackPane(lid, trim, lock);
        lidNode.setMinSize(w, h * 0.45);
        lidNode.setMaxSize(w, h * 0.45);
        lidNode.setTranslateY(-h * 0.28);

        StackPane chest = new StackPane(interior, body, lidNode);
        chest.setMinSize(w, h);
        chest.setMaxSize(w, h);

        // Strong glow tinted by rarity — pulses during the pre-reveal.
        DropShadow glow = new DropShadow();
        glow.setColor(rarityColor);
        glow.setRadius(28);
        glow.setSpread(0.25);
        chest.setEffect(glow);
        chest.setUserData(glow); // so the animator can pulse it

        StackPane.setAlignment(chest, Pos.CENTER);
        StackPane.setMargin(chest, new Insets(0, 0, 60, 0));
        return chest;
    }

    private VBox buildCardView() {
        double w = 240, h = 336;
        ImageView art = new ImageView();
        try {
            art.setImage(org.jackhuang.hmcl.server.CardImageCache.getImage(card));
        } catch (RuntimeException ignored) {
        }
        art.setFitWidth(w);
        art.setFitHeight(h);
        art.setPreserveRatio(true);
        art.setSmooth(true);

        StackPane frame = new StackPane(art);
        frame.setMinSize(w + 12, h + 12);
        frame.setMaxSize(w + 12, h + 12);
        frame.setStyle("-fx-border-color: " + card.getRarity().getColor() + ";"
                + " -fx-border-width: 3; -fx-border-radius: 12; -fx-background-radius: 12;"
                + " -fx-background-color: rgba(0,0,0,0.45);");

        DropShadow halo = new DropShadow();
        halo.setColor(rarityColor);
        halo.setRadius(36);
        halo.setSpread(0.30);
        frame.setEffect(halo);
        frame.setUserData(halo);

        Label name = new Label(card.getName());
        name.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;"
                + " -fx-effect: dropshadow(gaussian, " + card.getRarity().getColor() + ", 10, 0.4, 0, 0);");

        Label rarity = new Label(card.getRarity().getDisplayName().toUpperCase());
        rarity.setStyle("-fx-text-fill: " + card.getRarity().getColor() + ";"
                + " -fx-font-size: 12px; -fx-font-weight: bold;"
                + " -fx-effect: dropshadow(gaussian, " + card.getRarity().getColor() + ", 6, 0.6, 0, 0);");

        VBox box = new VBox(8, frame, name, rarity);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ============================================================================================
    //                                       Animation timeline
    // ============================================================================================

    private void start() {
        Rarity r = card.getRarity();

        // INFINITO: animación tope con transmutación por rarezas + agujero negro + reveal cósmico.
        if (r == Rarity.INFINITO) {
            startInfinitoAnimation();
            return;
        }

        // 1) Chest scales in from 0 with a tiny overshoot.
        ScaleTransition chestIn = new ScaleTransition(Duration.millis(380), chestNode);
        chestIn.setFromX(0); chestIn.setFromY(0);
        chestIn.setToX(1.0); chestIn.setToY(1.0);
        chestIn.setInterpolator(Interpolator.EASE_OUT);

        // 2) Chest "shaking with anticipation": rotation jitter + translation jitter + glow pulse.
        Duration shakeFor = shakeDurationFor(r);
        ParallelTransition shake = buildShake(chestNode, r, shakeFor);

        // 3) The chest opens. The lid lifts away and the glow bursts from inside.
        RotateTransition lidRotate = new RotateTransition(Duration.millis(520), lidNode);
        lidRotate.setToAngle(-24);
        lidRotate.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition lidLift = new TranslateTransition(Duration.millis(520), lidNode);
        lidLift.setToY(-92);
        lidLift.setToX(-10);
        lidLift.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition lidScale = new ScaleTransition(Duration.millis(520), lidNode);
        lidScale.setToX(1.08);
        lidScale.setToY(0.82);
        lidScale.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition chestPop = new ScaleTransition(Duration.millis(520), chestNode);
        chestPop.setToX(1.05);
        chestPop.setToY(1.05);
        chestPop.setAutoReverse(true);
        chestPop.setCycleCount(2);
        chestPop.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition openChest = new ParallelTransition(lidRotate, lidLift, lidScale, chestPop);
        openChest.setOnFinished(e -> {
            spawnParticles(r);
            if (r == Rarity.MITICA || r == Rarity.LEYENDA) {
                screenShake(r == Rarity.LEYENDA ? 14 : 8, r == Rarity.LEYENDA ? 18 : 10);
            }
        });

        // 4) Slight gap so the inner glow is visible before the card comes out.
        PauseTransition pause = new PauseTransition(Duration.millis(90));

        // 5) Card reveal: it rises from the chest, grows to full size and faces the player.
        FadeTransition cardFade = new FadeTransition(Duration.millis(720), cardNode);
        cardFade.setFromValue(0); cardFade.setToValue(1);
        ScaleTransition cardScale = new ScaleTransition(Duration.millis(760), cardNode);
        cardScale.setToX(1.0); cardScale.setToY(1.0);
        cardScale.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition cardRise = new TranslateTransition(Duration.millis(780), cardNode);
        cardRise.setToY(-34);
        cardRise.setInterpolator(Interpolator.EASE_OUT);
        RotateTransition cardTurn = new RotateTransition(Duration.millis(780), cardNode);
        cardTurn.setToAngle(0);
        cardTurn.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition reveal = new ParallelTransition(cardFade, cardScale, cardRise, cardTurn);

        // 6) Pulse the card halo continuously while the player looks at it.
        DropShadow halo = (DropShadow) cardNode.getChildren().get(0).getEffect();
        Timeline haloPulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(halo.radiusProperty(), 28.0),
                        new KeyValue(halo.spreadProperty(), 0.20)),
                new KeyFrame(Duration.millis(900),
                        new KeyValue(halo.radiusProperty(), 52.0),
                        new KeyValue(halo.spreadProperty(), 0.40)),
                new KeyFrame(Duration.millis(1800),
                        new KeyValue(halo.radiusProperty(), 28.0),
                        new KeyValue(halo.spreadProperty(), 0.20))
        );
        haloPulse.setCycleCount(Timeline.INDEFINITE);

        // 7) Enable the close button + (legendary) keep firing sparkle bursts.
        reveal.setOnFinished(e -> {
            closeButton.setOpacity(1);
            closeButton.setDisable(false);
            haloPulse.play();
            if (r == Rarity.LEYENDA) {
                startSparkleLoop();
            }
        });

        SequentialTransition seq = new SequentialTransition(chestIn, shake, openChest, pause, reveal);
        seq.play();
    }

    private Duration shakeDurationFor(Rarity r) {
        switch (r) {
            case LEYENDA:    return Duration.millis(1800);
            case MITICA:     return Duration.millis(1300);
            case RARA:       return Duration.millis(900);
            case POCO_COMUN: return Duration.millis(700);
            default:         return Duration.millis(600);
        }
    }

    private ParallelTransition buildShake(StackPane node, Rarity r, Duration total) {
        // Rotation jitter
        double angle = r == Rarity.LEYENDA ? 10 : (r == Rarity.MITICA ? 7 : 4);
        RotateTransition rot = new RotateTransition(Duration.millis(60), node);
        rot.setFromAngle(-angle); rot.setToAngle(angle);
        rot.setAutoReverse(true);
        rot.setCycleCount((int) (total.toMillis() / 60));

        // Translation jitter
        double trans = r == Rarity.LEYENDA ? 6 : (r == Rarity.MITICA ? 4 : 2);
        TranslateTransition tt = new TranslateTransition(Duration.millis(80), node);
        tt.setFromX(-trans); tt.setToX(trans);
        tt.setAutoReverse(true);
        tt.setCycleCount((int) (total.toMillis() / 80));

        // Glow pulse on the chest
        DropShadow chestGlow = (DropShadow) node.getEffect();
        Timeline glowPulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(chestGlow.radiusProperty(), 20.0),
                        new KeyValue(chestGlow.spreadProperty(), 0.15)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(chestGlow.radiusProperty(), 60.0),
                        new KeyValue(chestGlow.spreadProperty(), 0.55))
        );
        glowPulse.setAutoReverse(true);
        glowPulse.setCycleCount((int) (total.toMillis() / 200));

        return new ParallelTransition(rot, tt, glowPulse);
    }

    // ============================================================================================
    //                                          Particles
    // ============================================================================================

    private void spawnParticles(Rarity r) {
        int count;
        switch (r) {
            case LEYENDA:    count = 95; break;
            case MITICA:     count = 55; break;
            case RARA:       count = 32; break;
            case POCO_COMUN: count = 24; break;
            default:         count = 18; break;
        }
        for (int i = 0; i < count; i++) {
            spawnParticle(r);
        }
        // Bigger rarities also drop sparkly star-like overshines.
        if (r == Rarity.MITICA || r == Rarity.LEYENDA) {
            int sparkles = r == Rarity.LEYENDA ? 22 : 12;
            for (int i = 0; i < sparkles; i++) {
                spawnSparkle();
            }
        }
    }

    private void spawnParticle(Rarity r) {
        double radius = 2 + RANDOM.nextDouble() * 4;
        Circle particle = new Circle(radius);

        Color colour = mixWithWhite(rarityColor, RANDOM.nextDouble() * 0.4);
        particle.setFill(colour);
        DropShadow glow = new DropShadow(8, rarityColor);
        glow.setSpread(0.4);
        particle.setEffect(glow);

        // Spawn at the chest's centre.
        particle.setLayoutX(getPrefWidth() / 2);
        particle.setLayoutY(getPrefHeight() / 2 + 10);

        double angle = RANDOM.nextDouble() * Math.PI * 2;
        double distance = 110 + RANDOM.nextDouble() * (r == Rarity.LEYENDA ? 320
                : r == Rarity.MITICA ? 270 : r == Rarity.RARA ? 220 : 180);
        double dx = Math.cos(angle) * distance;
        double dy = Math.sin(angle) * distance;

        Duration dur = Duration.millis(750 + RANDOM.nextInt(700));
        TranslateTransition tt = new TranslateTransition(dur, particle);
        tt.setByX(dx);
        tt.setByY(dy);
        tt.setInterpolator(Interpolator.EASE_OUT);

        FadeTransition ft = new FadeTransition(dur, particle);
        ft.setFromValue(1); ft.setToValue(0);

        ScaleTransition st = new ScaleTransition(dur, particle);
        st.setToX(0.25); st.setToY(0.25);

        ParallelTransition pt = new ParallelTransition(tt, ft, st);
        pt.setOnFinished(e -> particleLayer.getChildren().remove(particle));
        particleLayer.getChildren().add(particle);
        pt.play();
    }

    private void spawnSparkle() {
        Rectangle sparkle = new Rectangle(3, 3, brighter(rarityColor, 0.7));
        sparkle.setRotate(45);
        DropShadow glow = new DropShadow(14, brighter(rarityColor, 0.9));
        glow.setSpread(0.7);
        sparkle.setEffect(glow);

        double cx = getPrefWidth() / 2 + (RANDOM.nextDouble() - 0.5) * 360;
        double cy = getPrefHeight() / 2 + (RANDOM.nextDouble() - 0.5) * 260;
        sparkle.setLayoutX(cx);
        sparkle.setLayoutY(cy);

        Duration dur = Duration.millis(700 + RANDOM.nextInt(500));
        ScaleTransition st = new ScaleTransition(dur, sparkle);
        st.setFromX(0.2); st.setFromY(0.2);
        st.setToX(2.2); st.setToY(2.2);

        FadeTransition ft = new FadeTransition(dur, sparkle);
        ft.setFromValue(1); ft.setToValue(0);

        ParallelTransition pt = new ParallelTransition(st, ft);
        pt.setOnFinished(e -> particleLayer.getChildren().remove(sparkle));
        particleLayer.getChildren().add(sparkle);
        pt.play();
    }

    /// While a legendary card is on screen, keep dropping ambient sparkles every ~250ms.
    private void startSparkleLoop() {
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(280), e -> {
            for (int i = 0; i < 3; i++) spawnSparkle();
        }));
        tl.setCycleCount(40); // about 11 seconds of ambient sparkle
        tl.play();
    }

    private void screenShake(int amplitude, int cycles) {
        TranslateTransition tt = new TranslateTransition(Duration.millis(45), this);
        tt.setFromX(-amplitude); tt.setToX(amplitude);
        tt.setAutoReverse(true);
        tt.setCycleCount(cycles);
        tt.setOnFinished(e -> setTranslateX(0));
        tt.play();
    }

    // ============================================================================================
    //                                            Helpers
    // ============================================================================================

    private static Color brighter(Color base, double amount) {
        double r = clamp01(base.getRed() + amount);
        double g = clamp01(base.getGreen() + amount);
        double b = clamp01(base.getBlue() + amount);
        return new Color(r, g, b, base.getOpacity());
    }

    private static Color mixWithWhite(Color base, double amount) {
        return new Color(
                clamp01(base.getRed() * (1 - amount) + amount),
                clamp01(base.getGreen() * (1 - amount) + amount),
                clamp01(base.getBlue() * (1 - amount) + amount),
                base.getOpacity());
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ============================================================================================
    //                                  INFINITO (animación tope)
    // ============================================================================================
    //
    // El cofre aparece gris (común), su glow va mutando por todas las rarezas (común → poco
    // común → rara → mítica → leyenda), después implosiona en un punto y se abre un agujero
    // negro magenta que escupe la carta con un efecto cósmico y arcoíris.
    //
    private void startInfinitoAnimation() {
        // Colores de cada rareza
        final Color[] colors = {
                Color.web("#9aa3b8"), // común
                Color.web("#45d483"), // poco común
                Color.web("#4ea6ff"), // rara
                Color.web("#b66cff"), // mítica
                Color.web("#ffaa22"), // leyenda
        };

        // Glow del cofre, lo manipulamos por timeline.
        DropShadow chestGlow = (DropShadow) chestNode.getEffect();
        chestGlow.setColor(colors[0]);
        chestGlow.setRadius(28);
        chestGlow.setSpread(0.30);

        // Header inicial misterioso.
        headerLabel.setText("¡¿ . . . ?!");
        headerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;"
                + " -fx-effect: dropshadow(gaussian, white, 16, 0.5, 0, 0);");

        // 1) Cofre aparece.
        ScaleTransition chestIn = new ScaleTransition(Duration.millis(420), chestNode);
        chestIn.setFromX(0); chestIn.setFromY(0);
        chestIn.setToX(1.0); chestIn.setToY(1.0);
        chestIn.setInterpolator(Interpolator.EASE_OUT);

        // 2) Transmutaciones: el glow va saltando de rareza en rareza. En cada paso lanzo un
        //    pequeño burst de partículas del color correspondiente.
        Timeline transmute = new Timeline();
        double stepMs = 700;
        for (int i = 1; i < colors.length; i++) {
            final int idx = i;
            transmute.getKeyFrames().add(new KeyFrame(
                    Duration.millis(stepMs * i),
                    new KeyValue(chestGlow.colorProperty(), colors[i]),
                    new KeyValue(chestGlow.radiusProperty(), 30 + i * 7),
                    new KeyValue(chestGlow.spreadProperty(), 0.30 + i * 0.05)));
            transmute.getKeyFrames().add(new KeyFrame(
                    Duration.millis(stepMs * i + 1),
                    e -> spawnInfinitoBurst(colors[idx], 18)));
        }
        transmute.getKeyFrames().add(new KeyFrame(
                Duration.millis(stepMs * (colors.length - 1) + 250),
                new KeyValue(chestGlow.radiusProperty(), 70),
                new KeyValue(chestGlow.spreadProperty(), 0.65)));

        // 3) Implosión: el cofre se contrae a un punto.
        ScaleTransition chestImplode = new ScaleTransition(Duration.millis(450), chestNode);
        chestImplode.setToX(0); chestImplode.setToY(0);
        chestImplode.setInterpolator(Interpolator.EASE_IN);
        FadeTransition chestFadeOut = new FadeTransition(Duration.millis(450), chestNode);
        chestFadeOut.setToValue(0);
        ParallelTransition implode = new ParallelTransition(chestImplode, chestFadeOut);
        implode.setOnFinished(e -> screenShake(20, 22));

        // 4) Agujero negro: círculo negro con anillo magenta y resplandor violeta.
        Circle blackHole = new Circle(0);
        blackHole.setFill(Color.BLACK);
        blackHole.setStroke(Color.web("#ff00ff"));
        blackHole.setStrokeWidth(5);
        DropShadow voidGlow = new DropShadow(60, Color.web("#ff00ff"));
        voidGlow.setSpread(0.55);
        blackHole.setEffect(voidGlow);
        Circle holeRing = new Circle(0);
        holeRing.setFill(Color.TRANSPARENT);
        holeRing.setStroke(Color.web("#ff80ff"));
        holeRing.setStrokeWidth(2);
        holeRing.setOpacity(0.7);
        // Los meto bajo la capa de partículas para que las partículas/sparkles se vean encima.
        int insertAt = Math.max(0, getChildren().indexOf(particleLayer));
        getChildren().add(insertAt, blackHole);
        getChildren().add(insertAt + 1, holeRing);
        StackPane.setAlignment(blackHole, Pos.CENTER);
        StackPane.setAlignment(holeRing, Pos.CENTER);

        Timeline holeGrow = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(blackHole.radiusProperty(), 0),
                        new KeyValue(holeRing.radiusProperty(), 0)),
                new KeyFrame(Duration.millis(900),
                        new KeyValue(blackHole.radiusProperty(), 150, Interpolator.EASE_OUT),
                        new KeyValue(holeRing.radiusProperty(), 200, Interpolator.EASE_OUT)));
        holeGrow.setOnFinished(e -> {
            // Anillo pulsante mientras está abierto.
            Timeline ringPulse = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(holeRing.radiusProperty(), 200),
                            new KeyValue(holeRing.opacityProperty(), 0.7)),
                    new KeyFrame(Duration.millis(900),
                            new KeyValue(holeRing.radiusProperty(), 240),
                            new KeyValue(holeRing.opacityProperty(), 0.25)));
            ringPulse.setAutoReverse(true);
            ringPulse.setCycleCount(Timeline.INDEFINITE);
            ringPulse.play();
            // Sparkles arcoíris saliendo del agujero.
            startRainbowSparkleLoop();
        });

        // 5) Pausa para apreciar el agujero negro.
        PauseTransition pauseBeforeCard = new PauseTransition(Duration.millis(450));

        // 6) Carta cósmica: aparece girando desde el centro del agujero.
        cardNode.setOpacity(0);
        cardNode.setScaleX(0); cardNode.setScaleY(0);
        cardNode.setTranslateY(0);
        cardNode.setRotate(-25);
        // Cambio el halo de la carta a magenta para coherencia.
        DropShadow cardHalo = (DropShadow) cardNode.getChildren().get(0).getEffect();
        cardHalo.setColor(Color.web("#ff00ff"));
        cardHalo.setSpread(0.45);
        cardHalo.setRadius(50);

        ScaleTransition cardScale = new ScaleTransition(Duration.millis(750), cardNode);
        cardScale.setFromX(0); cardScale.setFromY(0);
        cardScale.setToX(1.0); cardScale.setToY(1.0);
        cardScale.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition cardFade = new FadeTransition(Duration.millis(750), cardNode);
        cardFade.setFromValue(0); cardFade.setToValue(1);
        RotateTransition cardSpin = new RotateTransition(Duration.millis(900), cardNode);
        cardSpin.setFromAngle(-360);
        cardSpin.setToAngle(0);
        cardSpin.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition cardReveal = new ParallelTransition(cardScale, cardFade, cardSpin);

        cardReveal.setOnFinished(e -> {
            // Texto final, botón listo, halo cósmico pulsante.
            headerLabel.setText("¡¡INFINITO!! · " + card.getName());
            headerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;"
                    + " -fx-effect: dropshadow(gaussian, #ff00ff, 22, 0.6, 0, 0);");
            closeButton.setOpacity(1);
            closeButton.setDisable(false);
            Timeline cardPulse = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(cardHalo.radiusProperty(), 36.0),
                            new KeyValue(cardHalo.spreadProperty(), 0.35)),
                    new KeyFrame(Duration.millis(900),
                            new KeyValue(cardHalo.radiusProperty(), 70.0),
                            new KeyValue(cardHalo.spreadProperty(), 0.65)),
                    new KeyFrame(Duration.millis(1800),
                            new KeyValue(cardHalo.radiusProperty(), 36.0),
                            new KeyValue(cardHalo.spreadProperty(), 0.35)));
            cardPulse.setCycleCount(Timeline.INDEFINITE);
            cardPulse.play();
        });

        new SequentialTransition(chestIn, transmute, implode, holeGrow, pauseBeforeCard, cardReveal).play();
    }

    /// Pequeño burst de partículas del color dado en el centro del cofre.
    private void spawnInfinitoBurst(Color color, int count) {
        for (int i = 0; i < count; i++) {
            Circle p = new Circle(2 + RANDOM.nextDouble() * 3);
            p.setFill(color);
            DropShadow glow = new DropShadow(8, color);
            glow.setSpread(0.55);
            p.setEffect(glow);
            p.setLayoutX(getPrefWidth() / 2);
            p.setLayoutY(getPrefHeight() / 2 + 10);
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double dist = 60 + RANDOM.nextDouble() * 100;
            Duration dur = Duration.millis(550 + RANDOM.nextInt(350));
            TranslateTransition tt = new TranslateTransition(dur, p);
            tt.setByX(Math.cos(angle) * dist);
            tt.setByY(Math.sin(angle) * dist);
            FadeTransition ft = new FadeTransition(dur, p);
            ft.setFromValue(1); ft.setToValue(0);
            ParallelTransition pt = new ParallelTransition(tt, ft);
            pt.setOnFinished(e -> particleLayer.getChildren().remove(p));
            particleLayer.getChildren().add(p);
            pt.play();
        }
    }

    /// Mientras la carta INFINITO está visible, escupimos sparkles arcoíris desde el centro.
    private void startRainbowSparkleLoop() {
        Color[] rainbow = {
                Color.web("#ff00ff"), Color.web("#ff44ff"), Color.web("#aa00ff"),
                Color.web("#7700ff"), Color.web("#00aaff"), Color.web("#ffaa22"),
                Color.WHITE,
        };
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(180), e -> {
            for (int i = 0; i < 4; i++) {
                Color c = rainbow[RANDOM.nextInt(rainbow.length)];
                Rectangle sparkle = new Rectangle(3, 3, c);
                sparkle.setRotate(45);
                DropShadow g = new DropShadow(14, c);
                g.setSpread(0.7);
                sparkle.setEffect(g);
                double cx = getPrefWidth() / 2 + (RANDOM.nextDouble() - 0.5) * 380;
                double cy = getPrefHeight() / 2 + (RANDOM.nextDouble() - 0.5) * 280;
                sparkle.setLayoutX(cx);
                sparkle.setLayoutY(cy);
                Duration dur = Duration.millis(800 + RANDOM.nextInt(500));
                ScaleTransition st = new ScaleTransition(dur, sparkle);
                st.setFromX(0.2); st.setFromY(0.2);
                st.setToX(2.5); st.setToY(2.5);
                FadeTransition ft = new FadeTransition(dur, sparkle);
                ft.setFromValue(1); ft.setToValue(0);
                ParallelTransition pt = new ParallelTransition(st, ft);
                pt.setOnFinished(ev -> particleLayer.getChildren().remove(sparkle));
                particleLayer.getChildren().add(sparkle);
                pt.play();
            }
        }));
        tl.setCycleCount(60); // ~11s de fiesta arcoíris
        tl.play();
    }
}
