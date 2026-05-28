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

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.FXUtils;

import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loading splash shown before the main launcher window opens.
///
/// Sequence:
///  1. "Iniciando..."
///  2. "Buscando actualizaciones..."  ← while LauncherSelfUpdateService runs
///  3. "Todo correcto, continuando..." (or "Actualización disponible: vX")
///  4. ~800 ms pause so the user can read the message
///  5. Splash closes, main launcher window is shown
public final class SplashScreen {

    private static final long MIN_VISIBLE_MS = 1800;

    private final Stage stage;
    private final Label statusLabel;
    private final long shownAt;

    public SplashScreen() {
        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle(ServerLauncherConfig.SERVER_NAME);
        try {
            stage.getIcons().add(FXUtils.newBuiltinImage("/assets/branding/icon.png"));
        } catch (Exception ignored) {
        }

        ImageView logo = new ImageView(FXUtils.newBuiltinImage("/assets/branding/logo.png"));
        logo.setFitWidth(96);
        logo.setFitHeight(96);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        // Spin the barrel while loading — one full rotation every 2 s.
        RotateTransition spin = new RotateTransition(Duration.seconds(2), logo);
        spin.setByAngle(360);
        spin.setCycleCount(Animation.INDEFINITE);
        spin.setInterpolator(Interpolator.LINEAR);
        spin.play();

        Label title = new Label(ServerLauncherConfig.SERVER_NAME);
        title.getStyleClass().add("splash-title");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");

        Label subtitle = new Label("Launcher v" + Metadata.VERSION);
        subtitle.setStyle("-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 11px;");

        statusLabel = new Label("Iniciando...");
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.9); -fx-font-size: 13px;");

        ProgressBar progress = new ProgressBar();
        progress.setPrefWidth(280);
        progress.setMaxWidth(280);
        progress.setProgress(-1); // indeterminate

        VBox root = new VBox(10, logo, title, subtitle, statusLabel, progress);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(28, 36, 28, 36));
        // Match the launcher's dark/orange branding (same gradient as server-home card)
        root.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #120a06, #2d1810 58%, #120a06);"
                        + " -fx-background-radius: 12;"
                        + " -fx-border-color: rgba(160, 82, 27, 0.55);"
                        + " -fx-border-radius: 12;"
                        + " -fx-border-width: 1;"
        );

        Scene scene = new Scene(root, 380, 290);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.centerOnScreen();

        shownAt = System.currentTimeMillis();
    }

    public void show() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(stage::show);
        } else {
            stage.show();
        }
    }

    public void setStatus(String text) {
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(text);
        } else {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    /// Closes the splash, ensuring it was visible for at least MIN_VISIBLE_MS
    /// so the user actually sees the messages. Calls `onClosed` on the FX thread
    /// when the close completes.
    public void closeWhenReady(Runnable onClosed) {
        long elapsed = System.currentTimeMillis() - shownAt;
        long remaining = Math.max(0, MIN_VISIBLE_MS - elapsed);

        Timeline close = new Timeline(new KeyFrame(Duration.millis(remaining + 600), e -> {
            stage.close();
            if (onClosed != null) onClosed.run();
        }));
        close.play();
    }

    /// Runs the standard splash sequence:
    ///   1. Show
    ///   2. Set "Buscando actualizaciones..."
    ///   3. Asynchronously check for launcher updates
    ///   4. Set "Todo correcto, continuando..." (or update-available message)
    ///   5. Close splash and invoke `onComplete` on the FX thread.
    public void runSequence(Runnable onComplete) {
        show();
        setStatus("Buscando actualizaciones...");

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LauncherSelfUpdateService.fetchVersionInfo();
                    } catch (Exception e) {
                        LOG.warning("Splash: launcher update check failed (continuing offline)", e);
                        return null;
                    }
                })
                .whenComplete((info, error) -> Platform.runLater(() -> {
                    if (info != null && info.isNewerThanCurrent()) {
                        setStatus("Actualización disponible: v" + info.getLatest());
                    } else {
                        setStatus("Todo correcto, continuando...");
                    }
                    closeWhenReady(onComplete);
                }));
    }
}
