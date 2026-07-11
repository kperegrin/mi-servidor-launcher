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
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.stage.DirectoryChooser;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.server.BarrilmcInstallConfig;
import org.jackhuang.hmcl.server.BarrilmcLauncherPrefs;
import org.jackhuang.hmcl.server.BarrilmcVideoExporter;
import org.jackhuang.hmcl.server.LauncherSelfUpdateService;
import org.jackhuang.hmcl.server.LauncherUpdater;
import org.jackhuang.hmcl.server.LauncherVersionInfo;
import org.jackhuang.hmcl.server.ServerInstanceManager;
import org.jackhuang.hmcl.server.ServerLauncherConfig;
import org.jackhuang.hmcl.server.YouTubeFeedService;
import org.jackhuang.hmcl.server.YouTubePostsService;
import org.jackhuang.hmcl.server.ServerStatusService;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.account.MicrosoftAccountLoginPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Server-specific home page embedded in HMCL's main page.
@NotNullByDefault
public final class ServerHomeController extends FlowPane {
    private final Label statusLabel = new Label("Comprobando estado...");
    private final Label playersLabel = new Label("-");
    private final Label versionLabel = new Label(ServerLauncherConfig.MINECRAFT_VERSION);
    private final Label accountLabel = new Label();
    private final Label progressLabel = new Label("Listo");
    private final Label launcherUpdateLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final VBox videoListBox = new VBox(8);
    private final VBox postsListBox = new VBox(8);
    private final JFXButton playButton = new JFXButton("Jugar servidor");
    private final JFXButton playMenuButton = new JFXButton("Abrir sin conectar");
    private final JFXButton updateButton = new JFXButton("Actualizar archivos");
    private final JFXButton settingsButton = new JFXButton("Configuracion");
    private final JFXButton launcherUpdateButton = new JFXButton("Actualizar launcher");
    private final JFXButton microsoftButton = new JFXButton("Microsoft");
    private final JFXButton offlineButton = new JFXButton("Offline");
    private final Slider musicVolumeSlider = new Slider(0, 100, 50);
    private final Label musicVolumeLabel = new Label("50%");
    private final ServerStatusService statusService = new ServerStatusService();
    private static Clip backgroundMusicClip;
    private LauncherVersionInfo pendingLauncherUpdate;

    /// Creates the custom home.
    public ServerHomeController() {
        getStyleClass().add("server-home-root");
        setAlignment(Pos.TOP_CENTER);
        // Align the two columns to their top edges so the compact card doesn't
        // float vertically centered next to the taller side panel.
        setRowValignment(VPos.TOP);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setHgap(18);
        setVgap(18);
        setPadding(new Insets(24));

        ImageView logo = new ImageView(FXUtils.newBuiltinImage("/assets/branding/logo.png"));
        logo.setFitWidth(88);
        logo.setFitHeight(88);
        logo.setPreserveRatio(true);
        // Rounded corners via pixel clip
        Rectangle logoClip = new Rectangle(88, 88);
        logoClip.setArcWidth(20);
        logoClip.setArcHeight(20);
        logo.setClip(logoClip);
        // Wrapper with orange glow border (styled in CSS via .server-logo-container)
        StackPane logoWrapper = new StackPane(logo);
        logoWrapper.getStyleClass().add("server-logo-container");
        logoWrapper.setMinSize(90, 90);
        logoWrapper.setMaxSize(90, 90);

        Label title = new Label(ServerLauncherConfig.SERVER_NAME);
        title.getStyleClass().add("server-home-title");
        title.setWrapText(true);
        Label subtitle = new Label("Minecraft " + ServerLauncherConfig.MINECRAFT_VERSION + " + Fabric");
        subtitle.getStyleClass().add("server-home-subtitle");
        subtitle.setWrapText(true);

        VBox titleBox = new VBox(4, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox hero = new HBox(14, logoWrapper, titleBox);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("server-home-hero");

        FlowPane status = new FlowPane(10, 10,
                metric("Estado", statusLabel),
                metric("Jugadores", playersLabel),
                metric("Version", versionLabel),
                metric("Cuenta", accountLabel));
        status.setAlignment(Pos.CENTER);
        status.setPrefWrapLength(720);

        configureButtons();
        FlowPane actions = new FlowPane(10, 10,
                playButton, playMenuButton, updateButton, settingsButton);
        actions.setAlignment(Pos.CENTER);
        actions.setPrefWrapLength(720);

        FlowPane loginActions = new FlowPane(10, 10, microsoftButton, offlineButton);
        loginActions.setAlignment(Pos.CENTER);
        loginActions.setPrefWrapLength(720);

        HBox musicControls = buildMusicControls();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.getStyleClass().add("server-progress");
        progressLabel.getStyleClass().add("server-progress-label");
        // Keep the progress row hidden while idle so the card hugs its content
        // instead of reserving empty space. It is revealed on demand in setBusy().
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);
        launcherUpdateLabel.getStyleClass().add("server-progress-label");
        launcherUpdateButton.setVisible(false);
        launcherUpdateButton.setManaged(false);
        launcherUpdateLabel.setVisible(false);
        launcherUpdateLabel.setManaged(false);

        HBox launcherUpdate = new HBox(10, launcherUpdateLabel, launcherUpdateButton);
        launcherUpdate.setAlignment(Pos.CENTER);

        Hyperlink moveLink = new Hyperlink("Mover instalacion a otro disco...");
        moveLink.getStyleClass().add("server-progress-label");
        moveLink.setOnAction(e -> showMoveInstallDialog());

        // Main card (center) — width AND height adapt to content, not the full window.
        VBox mainCard = new VBox(12, hero, status, actions, loginActions, musicControls, progressBar, progressLabel, launcherUpdate, new Separator(), moveLink);
        mainCard.getStyleClass().add("server-home");
        mainCard.setAlignment(Pos.TOP_CENTER);
        mainCard.setPadding(new Insets(16));
        mainCard.setMinWidth(340);
        mainCard.setMaxWidth(Double.MAX_VALUE);
        // Hug the content vertically so the dark card doesn't stretch to fill the column.
        mainCard.setMaxHeight(Region.USE_PREF_SIZE);

        // Chat lives permanently under the main card (no dialog).
        ChatPanel chatPanel = new ChatPanel();
        chatPanel.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(chatPanel, Priority.ALWAYS);

        VBox leftColumn = new VBox(18, mainCard, chatPanel);
        leftColumn.setAlignment(Pos.TOP_CENTER);
        leftColumn.setFillWidth(true);
        leftColumn.setMinWidth(340);
        leftColumn.setPrefWidth(560);
        leftColumn.setMaxWidth(640);

        // Votes live permanently above the videos/posts side panel (no dialog).
        VotesPanel votesPanel = new VotesPanel();
        votesPanel.setMaxWidth(Double.MAX_VALUE);

        // Side panel: latest videos + posts
        VBox sidePanel = buildSidePanel();
        sidePanel.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(sidePanel, Priority.ALWAYS);

        VBox rightColumn = new VBox(18, votesPanel, sidePanel);
        rightColumn.setAlignment(Pos.TOP_CENTER);
        rightColumn.setFillWidth(true);
        rightColumn.setMinWidth(336);
        rightColumn.setPrefWidth(380);
        rightColumn.setMaxWidth(440);
        HBox.setHgrow(rightColumn, Priority.SOMETIMES);

        getChildren().setAll(leftColumn, rightColumn);

        Accounts.selectedAccountProperty().addListener((observable, oldValue, newValue) -> updateAccountLabel());
        updateAccountLabel();
        refreshStatus();
        refreshYouTubeVideos();
        refreshYouTubePosts();
        checkLauncherUpdate();
        Platform.runLater(ServerInstanceManager::getOrCreateServerProfile);

        // Start orange glow pulse on the play button
        startPlayButtonGlow();
    }

    private HBox buildMusicControls() {
        Label title = new Label("Musica");
        title.getStyleClass().add("server-audio-label");

        // Restore the last saved volume (or 50 if this is a fresh install).
        double savedVolume = BarrilmcLauncherPrefs.getMusicVolume(50);

        musicVolumeSlider.setMaxWidth(Double.MAX_VALUE);
        musicVolumeSlider.setValue(savedVolume);
        musicVolumeLabel.setText(Math.round(savedVolume) + "%");
        musicVolumeLabel.getStyleClass().add("server-progress-label");

        musicVolumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            double volume = Math.max(0, Math.min(100, newValue.doubleValue()));
            musicVolumeLabel.setText(Math.round(volume) + "%");
            setMusicVolume(volume / 100.0);
            // Persist on every change so the next launch starts at the same level.
            BarrilmcLauncherPrefs.setMusicVolume(volume);
        });

        HBox box = new HBox(10, title, musicVolumeSlider, musicVolumeLabel);
        box.getStyleClass().add("server-audio-panel");
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(musicVolumeSlider, Priority.ALWAYS);

        // Loading the background WAV takes ~1-2 seconds — do it off the UI thread so the launcher
        // window appears immediately. The volume is applied once the clip finishes opening.
        startBackgroundMusicAsync(savedVolume / 100.0);
        return box;
    }

    private void startBackgroundMusicAsync(double initialVolume) {
        Thread loader = new Thread(() -> {
            try {
                if (backgroundMusicClip == null) {
                    URL music = ServerHomeController.class.getResource("/assets/barrilmc/background.wav");
                    if (music == null) {
                        return;
                    }
                    try (InputStream input = music.openStream();
                         BufferedInputStream buffered = new BufferedInputStream(input);
                         AudioInputStream audio = AudioSystem.getAudioInputStream(buffered)) {
                        Clip clip = AudioSystem.getClip();
                        clip.open(audio);
                        backgroundMusicClip = clip;
                    }
                }
                Platform.runLater(() -> {
                    setMusicVolume(initialVolume);
                    if (!backgroundMusicClip.isRunning()) {
                        backgroundMusicClip.loop(Clip.LOOP_CONTINUOUSLY);
                    }
                });
            } catch (Exception e) {
                LOG.warning("Could not start background music", e);
            }
        }, "barrilmc-music-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void setMusicVolume(double linearVolume) {
        Clip clip = backgroundMusicClip;
        if (clip == null || !clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        double safe = Math.max(0.0001, Math.min(1.0, linearVolume));
        float decibels = (float) (20.0 * Math.log10(safe));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
    }

    private VBox buildSidePanel() {
        // --- Videos section ---
        Label videosTitle = new Label("Ultimos videos");
        videosTitle.getStyleClass().add("server-news-title");

        videoListBox.getStyleClass().add("server-news-box");
        videoListBox.getChildren().setAll(new Label("Cargando videos..."));

        ScrollPane videosScroll = new ScrollPane(videoListBox);
        videosScroll.getStyleClass().add("server-news-scroll");
        videosScroll.setFitToWidth(true);
        videosScroll.setPannable(false);
        videosScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        videosScroll.setMaxHeight(320);
        VBox.setVgrow(videosScroll, Priority.ALWAYS);

        // "See all" link button
        JFXButton seeAllVideos = new JFXButton("Ver canal en YouTube");
        seeAllVideos.getStyleClass().add("server-secondary-button");
        seeAllVideos.setMaxWidth(Double.MAX_VALUE);
        seeAllVideos.setOnAction(e -> FXUtils.openLink(ServerLauncherConfig.YOUTUBE_CHANNEL_URL));

        // --- Posts section ---
        Separator sep = new Separator();
        sep.setOpacity(0.25);

        Label postsTitle = new Label("Publicaciones de BarrilMC");
        postsTitle.getStyleClass().add("server-news-title");

        postsListBox.getStyleClass().add("server-news-box");
        postsListBox.getChildren().setAll(new Label("Cargando publicaciones..."));

        ScrollPane postsScroll = new ScrollPane(postsListBox);
        postsScroll.getStyleClass().add("server-news-scroll");
        postsScroll.setFitToWidth(true);
        postsScroll.setPannable(false);
        postsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        postsScroll.setMaxHeight(320);

        JFXButton seeAllPosts = new JFXButton("Ver mas publicaciones");
        seeAllPosts.getStyleClass().add("server-secondary-button");
        seeAllPosts.setMaxWidth(Double.MAX_VALUE);
        seeAllPosts.setOnAction(e -> FXUtils.openLink(ServerLauncherConfig.YOUTUBE_POSTS_URL));

        VBox sidePanel = new VBox(10,
                videosTitle, videosScroll, seeAllVideos,
                sep,
                postsTitle, postsScroll, seeAllPosts,
                buildRightsCard());
        sidePanel.getStyleClass().add("server-news-panel");
        sidePanel.setAlignment(Pos.TOP_LEFT);
        sidePanel.setPadding(new Insets(16));
        sidePanel.setPrefWidth(360);
        sidePanel.setMaxWidth(420);
        sidePanel.setMinWidth(336);
        return sidePanel;
    }

    private VBox buildRightsCard() {
        Label title = new Label("Derechos de ElKimiZG");
        title.getStyleClass().add("server-rights-title");

        Label body = new Label("Representacion de BarrilMC");
        body.getStyleClass().add("server-rights-body");
        body.setWrapText(true);

        VBox card = new VBox(4, title, body);
        card.getStyleClass().add("server-rights-card");
        return card;
    }

    private VBox metric(String title, Label value) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("server-metric-title");
        value.getStyleClass().add("server-metric-value");
        VBox box = new VBox(4, titleLabel, value);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("server-metric");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void configureButtons() {
        playButton.getStyleClass().addAll("server-primary-button", "dialog-accept");
        playButton.setStyle("-fx-text-fill: white;");
        playButton.setGraphic(SVG.ROCKET_LAUNCH.createIcon(18));
        playButton.setOnAction(event -> runUpdate(true, true));

        playMenuButton.getStyleClass().add("server-secondary-button");
        playMenuButton.setGraphic(SVG.HOME.createIcon(18));
        FXUtils.installFastTooltip(playMenuButton,
                "Abre el juego en el menu principal, sin conectarte automaticamente al servidor.");
        playMenuButton.setOnAction(event -> runUpdate(true, false));

        updateButton.getStyleClass().add("server-secondary-button");
        updateButton.setGraphic(SVG.UPDATE.createIcon(18));
        updateButton.setOnAction(event -> runUpdate(false, false));

        settingsButton.getStyleClass().add("server-secondary-button");
        settingsButton.setGraphic(SVG.SETTINGS.createIcon(18));
        settingsButton.setOnAction(event -> {
            Profile profile = ServerInstanceManager.getOrCreateServerProfile();
            Controllers.getSettingsPage().showGameSettings(profile);
            Controllers.navigate(Controllers.getSettingsPage());
        });

        launcherUpdateButton.getStyleClass().add("server-secondary-button");
        launcherUpdateButton.setGraphic(SVG.UPDATE.createIcon(18));
        launcherUpdateButton.setOnAction(event -> downloadLauncherUpdate());

        microsoftButton.getStyleClass().add("server-login-button");
        microsoftButton.setGraphic(SVG.MICROSOFT.createIcon(18));
        microsoftButton.setOnAction(event -> {
            // When an Azure client ID is configured (hmcl.microsoft.auth.id), use HMCL's
            // native flow: the browser opens, the user logs in, and the launcher captures
            // the code automatically via its localhost callback — no Ctrl+L / Ctrl+C.
            // Without a client ID, fall back to the manual clipboard flow so login still works.
            if (Accounts.OAUTH_CALLBACK.getClientId().isEmpty()) {
                Controllers.dialog(new LegacyMicrosoftLoginPane());
            } else {
                Controllers.dialog(new MicrosoftAccountLoginPane());
            }
        });

        offlineButton.getStyleClass().add("server-login-button");
        offlineButton.setGraphic(SVG.PERSON.createIcon(18));
        offlineButton.setOnAction(event -> Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_OFFLINE)));
    }

    private void runUpdate(boolean launchAfterUpdate, boolean quickJoin) {
        Profile profile = ServerInstanceManager.getOrCreateServerProfile();
        setBusy(true);
        updateProgress("Preparando actualizacion", 0);

        // Export the latest YouTube thumbnail into the instance (fire-and-forget). The in-game
        // mod reads <instance>/barrilmc/video.png instead of decoding JPEG itself, which is
        // unreliable inside Fabric's classloader. Runs well before the game reaches its menu.
        CompletableFuture.runAsync(() -> BarrilmcVideoExporter.export(ServerLauncherConfig.INSTANCE_DIRECTORY));

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LauncherUpdater.prepare(profile, this::updateProgress);
                    } catch (Exception e) {
                        throw new UpdateFailedException(e);
                    }
                })
                .whenComplete((manifest, throwable) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        LOG.warning("Server update failed", cause);
                        progressLabel.setText("Error al actualizar");
                        Controllers.dialog(
                                "No se pudo preparar la instancia del servidor.\n\n" + StringUtils.getStackTrace(cause),
                                "Actualizacion fallida",
                                MessageDialogPane.MessageType.ERROR);
                        return;
                    }

                    ServerInstanceManager.applyLaunchSettings(profile, manifest, quickJoin);
                    versionLabel.setText(manifest.getMinecraftVersion() + " / Fabric " + manifest.getLoaderVersion());
                    progressLabel.setText("Archivos verificados");
                    refreshStatus();
                    if (launchAfterUpdate) {
                        Versions.launch(profile, ServerLauncherConfig.INSTANCE_NAME);
                    }
                }));
    }

    private void checkLauncherUpdate() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LauncherSelfUpdateService.fetchVersionInfo();
                    } catch (Exception e) {
                        throw new UpdateFailedException(e);
                    }
                })
                .whenComplete((info, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || info == null || !info.isNewerThanCurrent()) {
                        return;
                    }
                    pendingLauncherUpdate = info;
                    launcherUpdateLabel.setText("Launcher " + info.getLatest() + " disponible");
                    launcherUpdateLabel.setVisible(true);
                    launcherUpdateLabel.setManaged(true);
                    launcherUpdateButton.setVisible(true);
                    launcherUpdateButton.setManaged(true);
                    downloadLauncherUpdate();
                }));
    }

    private void downloadLauncherUpdate() {
        LauncherVersionInfo info = pendingLauncherUpdate;
        if (info == null) {
            return;
        }

        setBusy(true);
        updateProgress("Preparando descarga del launcher", 0);

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LauncherSelfUpdateService.download(info, this::updateProgress);
                    } catch (Exception e) {
                        throw new UpdateFailedException(e);
                    }
                })
                .whenComplete((path, throwable) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        LOG.warning("Launcher self update failed", cause);
                        progressLabel.setText("Error al actualizar launcher");
                        Controllers.dialog(
                                "No se pudo descargar la nueva version del launcher.\n\n" + StringUtils.getStackTrace(cause),
                                "Actualizacion fallida",
                                MessageDialogPane.MessageType.ERROR);
                        return;
                    }

                    Path downloaded = path;
                    try {
                        LauncherSelfUpdateService.replaceCurrentLauncherAndRestart(downloaded);
                        Platform.exit();
                        System.exit(0);
                    } catch (Exception e) {
                        LOG.warning("Could not start downloaded launcher", e);
                        progressLabel.setText("Launcher descargado");
                        Controllers.dialog(
                                "Nueva version descargada:\n" + downloaded + "\n\nNo se pudo abrir automaticamente. Ejecuta ese archivo manualmente.",
                                "Actualizacion lista",
                                MessageDialogPane.MessageType.INFO);
                    }
                }));
    }

    private void showMoveInstallDialog() {
        Path currentDir = ServerLauncherConfig.LAUNCHER_DIRECTORY;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Selecciona la carpeta destino para la instalacion");
        File chosen = chooser.showDialog(getScene().getWindow());
        if (chosen == null) return;

        Path newDir = chosen.toPath().resolve("BarrilMCLauncher").toAbsolutePath().normalize();
        if (newDir.equals(currentDir)) {
            Alert same = new Alert(Alert.AlertType.WARNING);
            same.setTitle("Misma ubicacion");
            same.setHeaderText(null);
            same.setContentText("La carpeta seleccionada es la ubicacion actual. Elige otra.");
            same.showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Mover instalacion");
        confirm.setHeaderText("Mover instalacion a otro disco");
        confirm.setContentText(
                "Se copiaran todos los archivos de:\n" + currentDir +
                "\n\na:\n" + newDir +
                "\n\nLa carpeta original NO se borrara — puedes eliminarla manualmente despues de comprobar que todo funciona." +
                "\n\nEl launcher se cerrara al terminar. ¿Continuar?");
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            setBusy(true);
            updateProgress("Moviendo instalacion...", -1);

            CompletableFuture.runAsync(() -> {
                try {
                    BarrilmcInstallConfig.moveInstallation(currentDir, newDir, (done, total, file) ->
                            updateProgress("Copiando " + done + "/" + total + ": " + file,
                                    total > 0 ? (double) done / total : 0));
                } catch (IOException ex) {
                    throw new CompletionException(ex);
                }
            }).whenComplete((v, err) -> Platform.runLater(() -> {
                setBusy(false);
                if (err != null) {
                    LOG.warning("Move installation failed", err);
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("Error");
                    error.setHeaderText("Error al mover la instalacion");
                    error.setContentText(err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
                    error.showAndWait();
                    return;
                }
                Alert done = new Alert(Alert.AlertType.INFORMATION);
                done.setTitle("Instalacion movida");
                done.setHeaderText(null);
                done.setContentText("Instalacion movida correctamente a:\n" + newDir +
                        "\n\nEl launcher se cerrara ahora. Vuelve a abrirlo para usar la nueva ubicacion.");
                done.showAndWait();
                Platform.exit();
                System.exit(0);
            }));
        });
    }

    private void updateProgress(String message, double progress) {
        Platform.runLater(() -> {
            progressLabel.setText(message);
            progressBar.setProgress(progress < 0
                    ? ProgressIndicator.INDETERMINATE_PROGRESS
                    : Math.max(0, Math.min(1, progress)));
        });
    }

    private void setBusy(boolean busy) {
        playButton.setDisable(busy);
        playMenuButton.setDisable(busy);
        updateButton.setDisable(busy);
        settingsButton.setDisable(busy);
        launcherUpdateButton.setDisable(busy);
        microsoftButton.setDisable(busy);
        offlineButton.setDisable(busy);
        // Reveal the progress row as soon as work begins (it stays hidden while idle).
        if (busy) {
            progressBar.setVisible(true);
            progressBar.setManaged(true);
            progressLabel.setVisible(true);
            progressLabel.setManaged(true);
        }
        progressBar.setProgress(busy ? ProgressIndicator.INDETERMINATE_PROGRESS : progressBar.getProgress());
    }

    private void refreshStatus() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LauncherUpdater.fetchManifest().getServer();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .thenCompose(server -> statusService.queryAsync(
                        server == null ? ServerLauncherConfig.SERVER_IP : server.getIp(),
                        server == null ? ServerLauncherConfig.SERVER_PORT : server.getPort()))
                .thenAccept(status -> Platform.runLater(() -> {
                    if (status.online()) {
                        statusLabel.setText("Online");
                        playersLabel.setText(status.onlinePlayers() + " / " + status.maxPlayers());
                        if (status.version() != null && !status.version().isBlank()) {
                            versionLabel.setText(status.version());
                        }
                    } else {
                        statusLabel.setText("Offline");
                        playersLabel.setText("-");
                    }
                }));
    }

    private void refreshYouTubeVideos() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return YouTubeFeedService.fetchVideosByHandle(ServerLauncherConfig.YOUTUBE_CHANNEL_HANDLE);
                    } catch (Exception e) {
                        LOG.warning("Could not fetch YouTube videos", e);
                        return null;
                    }
                })
                .thenAccept(videos -> Platform.runLater(() -> {
                    if (videos == null || videos.isEmpty()) {
                        videoListBox.getChildren().setAll(new Label("No se pudieron cargar los videos."));
                        return;
                    }
                    videoListBox.getChildren().setAll(
                            videos.stream().limit(8).map(this::buildVideoCard).toList()
                    );
                }));
    }

    private void refreshYouTubePosts() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return YouTubePostsService.fetchPosts(ServerLauncherConfig.YOUTUBE_CHANNEL_HANDLE);
                    } catch (Exception e) {
                        LOG.warning("Could not fetch YouTube posts", e);
                        return null;
                    }
                })
                .thenAccept(posts -> Platform.runLater(() -> {
                    if (posts == null || posts.isEmpty()) {
                        postsListBox.getChildren().setAll(new Label("No se pudieron cargar las publicaciones."));
                        return;
                    }
                    postsListBox.getChildren().setAll(
                            posts.stream().limit(6).map(this::buildPostCard).toList()
                    );
                }));
    }

    private VBox buildPostCard(YouTubePostsService.PostEntry entry) {
        Label textLabel = new Label(entry.getSnippet());
        textLabel.getStyleClass().add("server-news-item-title");
        textLabel.setWrapText(true);

        Label timeLabel = new Label(entry.getPublishedTime());
        timeLabel.getStyleClass().add("server-news-item-body");

        VBox card = new VBox(6);
        card.getStyleClass().addAll("server-news-item", "server-post-card");
        card.getChildren().addAll(textLabel, timeLabel);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> FXUtils.openLink(entry.getUrl()));
        addCardHoverAnimation(card);

        // Download the image with proper browser headers (JavaFX Image class does
        // not send a User-Agent and some Google CDN URLs silently refuse it).
        if (entry.getImageUrl() != null && !entry.getImageUrl().isBlank()) {
            final String imgUrl = entry.getImageUrl();
            CompletableFuture.supplyAsync(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(imgUrl).openConnection();
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/124.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Referer", "https://www.youtube.com/");
                    conn.setConnectTimeout(8_000);
                    conn.setReadTimeout(12_000);
                    try (InputStream is = conn.getInputStream()) {
                        byte[] bytes = is.readAllBytes();
                        return new Image(new ByteArrayInputStream(bytes));
                    }
                } catch (Exception e) {
                    LOG.warning("Failed to load post image: " + imgUrl, e);
                    return null;
                }
            }).thenAccept(image -> Platform.runLater(() -> {
                if (image != null && !image.isError()) {
                    ImageView imageView = new ImageView(image);
                    imageView.setFitWidth(300);
                    imageView.setFitHeight(169);
                    imageView.setPreserveRatio(true);
                    imageView.setSmooth(true);
                    imageView.getStyleClass().add("server-post-image");
                    card.getChildren().add(0, imageView);
                }
            }));
        }
        return card;
    }

    private VBox buildVideoCard(YouTubeFeedService.VideoEntry entry) {
        // Thumbnail loaded in the background so the UI doesn't block.
        Image thumb = new Image(entry.getThumbnailUrl(), 300, 169, true, true, true);
        ImageView thumbView = new ImageView(thumb);
        thumbView.setFitWidth(300);
        thumbView.setFitHeight(169);
        thumbView.setPreserveRatio(true);
        thumbView.setSmooth(true);
        thumbView.getStyleClass().add("server-video-thumb");

        Label titleLabel = new Label(entry.getTitle());
        titleLabel.getStyleClass().add("server-news-item-title");
        titleLabel.setWrapText(true);

        Label dateLabel = new Label(entry.getFormattedDate());
        dateLabel.getStyleClass().add("server-news-item-body");

        VBox card = new VBox(6, thumbView, titleLabel, dateLabel);
        card.getStyleClass().add("server-news-item");
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> FXUtils.openLink(entry.getUrl()));
        addCardHoverAnimation(card);
        return card;
    }

    /// Smooth scale-up on mouse enter, scale-back on exit.
    private static void addCardHoverAnimation(VBox card) {
        ScaleTransition zoomIn = new ScaleTransition(Duration.millis(140), card);
        zoomIn.setToX(1.025);
        zoomIn.setToY(1.025);

        ScaleTransition zoomOut = new ScaleTransition(Duration.millis(140), card);
        zoomOut.setToX(1.0);
        zoomOut.setToY(1.0);

        card.setOnMouseEntered(e -> { zoomOut.stop(); zoomIn.playFromStart(); });
        card.setOnMouseExited(e -> { zoomIn.stop(); zoomOut.playFromStart(); });
    }

    /// Pulsing orange glow on the play button so it draws the eye.
    private void startPlayButtonGlow() {
        DropShadow glow = new DropShadow();
        glow.setColor(Color.web("#ff9000", 0.62));
        glow.setRadius(10);
        glow.setSpread(0.05);
        playButton.setEffect(glow);

        Timeline pulse = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(glow.radiusProperty(),  8.0),
                new KeyValue(glow.spreadProperty(), 0.04)),
            new KeyFrame(Duration.millis(1600),
                new KeyValue(glow.radiusProperty(), 22.0),
                new KeyValue(glow.spreadProperty(), 0.13)),
            new KeyFrame(Duration.millis(3200),
                new KeyValue(glow.radiusProperty(),  8.0),
                new KeyValue(glow.spreadProperty(), 0.04))
        );
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
    }

    private void updateAccountLabel() {
        Account account = Accounts.getSelectedAccount();
        if (account == null) {
            accountLabel.setText("Sin cuenta");
        } else if (account instanceof MicrosoftAccount) {
            accountLabel.setText("Microsoft: " + account.getCharacter());
        } else if (account instanceof OfflineAccount) {
            accountLabel.setText("Offline: " + account.getCharacter());
        } else {
            accountLabel.setText(account.getCharacter());
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof UpdateFailedException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class UpdateFailedException extends RuntimeException {
        private UpdateFailedException(Throwable cause) {
            super(cause);
        }
    }
}
