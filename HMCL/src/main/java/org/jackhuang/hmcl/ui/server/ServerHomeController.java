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
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.server.LauncherSelfUpdateService;
import org.jackhuang.hmcl.server.LauncherUpdater;
import org.jackhuang.hmcl.server.LauncherVersionInfo;
import org.jackhuang.hmcl.server.ServerInstanceManager;
import org.jackhuang.hmcl.server.ServerLauncherConfig;
import org.jackhuang.hmcl.server.YouTubeFeedService;
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

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Server-specific home page embedded in HMCL's main page.
@NotNullByDefault
public final class ServerHomeController extends HBox {
    private final Label statusLabel = new Label("Comprobando estado...");
    private final Label playersLabel = new Label("-");
    private final Label versionLabel = new Label(ServerLauncherConfig.MINECRAFT_VERSION);
    private final Label accountLabel = new Label();
    private final Label progressLabel = new Label("Listo");
    private final Label launcherUpdateLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final VBox videoListBox = new VBox(8);
    private final JFXButton playButton = new JFXButton("Jugar servidor");
    private final JFXButton updateButton = new JFXButton("Actualizar archivos");
    private final JFXButton settingsButton = new JFXButton("Configuracion");
    private final JFXButton launcherUpdateButton = new JFXButton("Actualizar launcher");
    private final JFXButton microsoftButton = new JFXButton("Microsoft");
    private final JFXButton offlineButton = new JFXButton("Offline");
    private final ServerStatusService statusService = new ServerStatusService();
    private LauncherVersionInfo pendingLauncherUpdate;

    /// Creates the custom home.
    public ServerHomeController() {
        setAlignment(Pos.TOP_CENTER);
        setSpacing(16);
        setPadding(new Insets(0));

        ImageView logo = new ImageView(FXUtils.newBuiltinImage("/assets/branding/logo.png"));
        logo.setFitWidth(72);
        logo.setFitHeight(72);
        logo.setPreserveRatio(true);

        Label title = new Label(ServerLauncherConfig.SERVER_NAME);
        title.getStyleClass().add("server-home-title");
        title.setWrapText(true);
        Label subtitle = new Label("Minecraft " + ServerLauncherConfig.MINECRAFT_VERSION + " + Fabric");
        subtitle.getStyleClass().add("server-home-subtitle");
        subtitle.setWrapText(true);

        VBox titleBox = new VBox(4, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox hero = new HBox(14, logo, titleBox);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("server-home-hero");

        FlowPane status = new FlowPane(10, 10,
                metric("Estado", statusLabel),
                metric("Jugadores", playersLabel),
                metric("Version", versionLabel),
                metric("Cuenta", accountLabel));
        status.setAlignment(Pos.CENTER);

        configureButtons();
        FlowPane actions = new FlowPane(10, 10, playButton, updateButton, settingsButton);
        actions.setAlignment(Pos.CENTER);

        FlowPane loginActions = new FlowPane(10, 10, microsoftButton, offlineButton);
        loginActions.setAlignment(Pos.CENTER);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.getStyleClass().add("server-progress");
        progressLabel.getStyleClass().add("server-progress-label");
        launcherUpdateLabel.getStyleClass().add("server-progress-label");
        launcherUpdateButton.setVisible(false);
        launcherUpdateButton.setManaged(false);
        launcherUpdateLabel.setVisible(false);
        launcherUpdateLabel.setManaged(false);

        HBox launcherUpdate = new HBox(10, launcherUpdateLabel, launcherUpdateButton);
        launcherUpdate.setAlignment(Pos.CENTER);

        // Main card (center)
        VBox mainCard = new VBox(12, hero, status, actions, loginActions, progressBar, progressLabel, launcherUpdate);
        mainCard.getStyleClass().add("server-home");
        mainCard.setAlignment(Pos.CENTER);
        mainCard.setPadding(new Insets(16));
        mainCard.setPrefWidth(560);
        mainCard.setMaxWidth(560);

        // Side panel: latest videos + posts
        VBox sidePanel = buildSidePanel();

        getChildren().setAll(mainCard, sidePanel);

        Accounts.selectedAccountProperty().addListener((observable, oldValue, newValue) -> updateAccountLabel());
        updateAccountLabel();
        refreshStatus();
        refreshYouTubeVideos();
        checkLauncherUpdate();
        Platform.runLater(ServerInstanceManager::getOrCreateServerProfile);
    }

    private VBox buildSidePanel() {
        // --- Videos section ---
        Label videosTitle = new Label("Últimos videos");
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
        JFXButton seeAllVideos = new JFXButton("Ver canal en YouTube →");
        seeAllVideos.getStyleClass().add("server-secondary-button");
        seeAllVideos.setMaxWidth(Double.MAX_VALUE);
        seeAllVideos.setOnAction(e -> FXUtils.openLink(ServerLauncherConfig.YOUTUBE_CHANNEL_URL));

        // --- Posts section ---
        Separator sep = new Separator();
        sep.setOpacity(0.25);

        Label postsTitle = new Label("Publicaciones de BarrilMC");
        postsTitle.getStyleClass().add("server-news-title");

        JFXButton postsButton = new JFXButton("Ver publicaciones");
        postsButton.getStyleClass().add("server-secondary-button");
        postsButton.setMaxWidth(Double.MAX_VALUE);
        postsButton.setOnAction(e -> FXUtils.openLink(ServerLauncherConfig.YOUTUBE_POSTS_URL));

        VBox sidePanel = new VBox(10,
                videosTitle, videosScroll, seeAllVideos,
                sep,
                postsTitle, postsButton);
        sidePanel.getStyleClass().add("server-news-panel");
        sidePanel.setAlignment(Pos.TOP_LEFT);
        sidePanel.setPadding(new Insets(16));
        sidePanel.setPrefWidth(300);
        sidePanel.setMaxWidth(320);
        sidePanel.setMinWidth(240);
        return sidePanel;
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
        playButton.setGraphic(SVG.ROCKET_LAUNCH.createIcon(18));
        playButton.setOnAction(event -> runUpdate(true));

        updateButton.getStyleClass().add("server-secondary-button");
        updateButton.setGraphic(SVG.UPDATE.createIcon(18));
        updateButton.setOnAction(event -> runUpdate(false));

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
        microsoftButton.setOnAction(event -> Controllers.dialog(new MicrosoftAccountLoginPane()));

        offlineButton.getStyleClass().add("server-login-button");
        offlineButton.setGraphic(SVG.PERSON.createIcon(18));
        offlineButton.setOnAction(event -> Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_OFFLINE)));
    }

    private void runUpdate(boolean launchAfterUpdate) {
        Profile profile = ServerInstanceManager.getOrCreateServerProfile();
        setBusy(true);
        updateProgress("Preparando actualizacion", 0);

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

                    ServerInstanceManager.applyLaunchSettings(profile, manifest);
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
                    progressLabel.setText("Launcher descargado");
                    Controllers.dialog(
                            "Nueva version descargada:\n" + downloaded + "\n\nCierra este launcher y ejecuta el archivo nuevo.",
                            "Actualizacion lista",
                            MessageDialogPane.MessageType.INFO);
                }));
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
        updateButton.setDisable(busy);
        settingsButton.setDisable(busy);
        launcherUpdateButton.setDisable(busy);
        microsoftButton.setDisable(busy);
        offlineButton.setDisable(busy);
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

    private VBox buildVideoCard(YouTubeFeedService.VideoEntry entry) {
        // Thumbnail — loaded in the background so the UI doesn't block
        Image thumb = new Image(entry.getThumbnailUrl(), 268, 151, true, true, true);
        ImageView thumbView = new ImageView(thumb);
        thumbView.setFitWidth(268);
        thumbView.setFitHeight(151);
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
        return card;
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
