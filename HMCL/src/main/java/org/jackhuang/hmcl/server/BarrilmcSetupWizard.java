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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// First-run setup wizard that lets the user choose an install location before any game files
/// are downloaded. Only shown when no custom path has been configured AND no game data exists yet.
///
/// If the user picks a non-default location the wizard saves it to the bootstrap config and
/// returns {@code true} — the caller must restart the JVM so all static path constants pick up
/// the new value.
@NotNullByDefault
public final class BarrilmcSetupWizard {

    private BarrilmcSetupWizard() {}

    /// Runs the first-run wizard if needed.
    ///
    /// @return {@code true} if the user chose a custom location and the JVM must be restarted.
    public static boolean runIfNeeded(@Nullable Stage owner) {
        if (BarrilmcInstallConfig.getCustomInstallPath() != null) {
            return false;
        }
        // If game data already exists in the default location, skip silently.
        if (Files.exists(ServerLauncherConfig.LAUNCHER_DIRECTORY.resolve(".barrilmc"))) {
            return false;
        }
        return showDialog(owner);
    }

    private static boolean showDialog(@Nullable Stage owner) {
        Path defaultDir = ServerLauncherConfig.LAUNCHER_DIRECTORY;
        final Path[] chosen = { null };

        // --- Content ---
        Label heading = new Label("¿Donde instalar los archivos del launcher?");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        heading.setWrapText(true);

        Label body = new Label(
                "Los mods, mundos y archivos del juego se guardan en una carpeta local.\n" +
                "Por defecto van al disco C:, pero si lo tienes lleno puedes cambiar\n" +
                "la ubicacion ahora — es mas facil hacerlo antes de que se descargue nada.");
        body.setWrapText(true);
        body.setStyle("-fx-text-fill: -fx-text-base-color; -fx-font-size: 13px;");

        Label pathTitle = new Label("Ubicacion actual:");
        pathTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: derive(-fx-text-base-color, 30%);");

        Label pathLabel = new Label(defaultDir.toString());
        pathLabel.setWrapText(true);
        pathLabel.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        Button changeBtn = new Button("Cambiar...");
        changeBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Selecciona la carpeta de instalacion");
            // Pre-set initial directory to the drive root if C: is full
            chooser.setInitialDirectory(new File(defaultDir.getRoot().toString()));
            File picked = chooser.showDialog(owner);
            if (picked == null) return;
            Path newPath = picked.toPath().resolve("BarrilMCLauncher").toAbsolutePath().normalize();
            if (newPath.equals(defaultDir)) {
                chosen[0] = null;
                pathLabel.setText(defaultDir.toString());
            } else {
                chosen[0] = newPath;
                pathLabel.setText(newPath.toString() + "  ✓ cambiado");
            }
        });

        HBox pathRow = new HBox(10, pathLabel, changeBtn);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        pathRow.setStyle(
                "-fx-background-color: derive(-fx-background, -5%);" +
                "-fx-border-color: derive(-fx-background, -15%);" +
                "-fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12 8 12;");

        VBox content = new VBox(14);
        content.setPadding(new Insets(4, 0, 4, 0));
        content.setPrefWidth(500);

        // Optional logo
        try (InputStream is = BarrilmcSetupWizard.class.getResourceAsStream("/assets/branding/logo.png")) {
            if (is != null) {
                ImageView logo = new ImageView(new Image(is, 48, 48, true, true));
                HBox logoRow = new HBox(logo);
                logoRow.setAlignment(Pos.CENTER);
                content.getChildren().add(logoRow);
            }
        } catch (Exception ignored) {}

        content.getChildren().addAll(heading, body, pathTitle, pathRow);

        // --- Dialog ---
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Configuracion inicial - BarrilMC Launcher");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPadding(new Insets(20, 24, 8, 24));

        ButtonType continueBtn = new ButtonType("Continuar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(continueBtn);

        // Apply "dialog-accept" style to the continue button so it matches the orange theme
        dialog.getDialogPane().lookupButton(continueBtn).getStyleClass().add("dialog-accept");

        ButtonType result = dialog.showAndWait().orElse(null);
        if (result != continueBtn || chosen[0] == null) {
            return false;
        }

        try {
            BarrilmcInstallConfig.setCustomInstallPath(chosen[0]);
            LOG.info("Setup wizard: install path set to " + chosen[0]);
            return true;
        } catch (IOException ex) {
            LOG.warning("Setup wizard: failed to save install path", ex);
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Error");
            err.setHeaderText("No se pudo guardar la ubicacion");
            err.setContentText(ex.getMessage() + "\n\nSe usara la ubicacion por defecto.");
            err.showAndWait();
            return false;
        }
    }

    /// Restarts the current launcher executable. Call this after the wizard returns {@code true}.
    public static void restart() {
        Path exe = JarUtils.thisJarPath();
        if (exe == null) {
            LOG.warning("Setup wizard restart: thisJarPath is null, cannot restart");
            return;
        }
        try {
            new ProcessBuilder(exe.toAbsolutePath().toString())
                    .inheritIO()
                    .start();
        } catch (IOException ex) {
            LOG.warning("Setup wizard restart failed", ex);
        }
    }
}
