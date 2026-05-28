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
import com.jfoenix.controls.JFXSpinner;
import com.jfoenix.controls.JFXTextArea;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftService;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftSession;
import org.jackhuang.hmcl.server.LegacyMicrosoftAuth;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;

import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Microsoft login using the official Minecraft launcher's public credentials
/// (client ID 00000000402b5328 + login.live.com/oauth20_desktop.srf redirect).
///
/// Because the redirect URI isn't on localhost, we can't auto-capture the
/// callback. Flow:
///   1. User clicks "Iniciar sesión" → system browser opens to login.live.com
///   2. After authenticating, the browser lands on a blank page whose URL has
///      `?code=...`
///   3. User copies that URL (or just the code) and pastes it back here
///   4. We exchange the code for an access token and run the Xbox Live → XSTS
///      → Minecraft chain via MicrosoftService.authenticateWithToken(useMBISSL=true)
public final class LegacyMicrosoftLoginPane extends JFXDialogLayout implements DialogAware {

    // MicrosoftService is constructed locally; the OAuth callback it carries is
    // only used by the OAuth flow we bypass, so a stub is safe.
    private final MicrosoftService service = new MicrosoftService(Accounts.OAUTH_CALLBACK);

    private final JFXButton btnOpenBrowser;
    private final JFXButton btnContinue;
    private final JFXButton btnCancel;
    private final JFXTextArea pasteField;
    private final Label statusLabel;
    private final SpinnerPane continueSpinner;
    private final VBox body;

    private TaskExecutor loginTask;
    private boolean browserOpened = false;

    public LegacyMicrosoftLoginPane() {
        getStyleClass().add("microsoft-login-dialog");

        Label heading = new Label("Añadir una cuenta Microsoft");
        heading.getStyleClass().add("header-label");
        setHeading(heading);

        this.setMaxWidth(650);

        Label step1 = new Label("1. Pulsa el botón y se abrirá tu navegador para iniciar sesión.");
        step1.setWrapText(true);
        Label step2 = new Label("2. Después de iniciar sesión verás una página en blanco. Copia la URL completa de la barra de direcciones.");
        step2.setWrapText(true);
        Label step3 = new Label("3. Pega esa URL aquí abajo y pulsa Continuar.");
        step3.setWrapText(true);

        btnOpenBrowser = new JFXButton("Abrir navegador para iniciar sesión");
        btnOpenBrowser.getStyleClass().add("dialog-accept");
        btnOpenBrowser.setOnAction(e -> openBrowser());

        pasteField = new JFXTextArea();
        pasteField.setPromptText("Pega aquí la URL de la página en blanco (https://login.live.com/oauth20_desktop.srf?code=...)");
        pasteField.setWrapText(true);
        pasteField.setPrefRowCount(3);
        pasteField.setDisable(true);

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("server-progress-label");

        body = new VBox(10, step1, step2, step3, btnOpenBrowser, pasteField, statusLabel);
        body.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(pasteField, Priority.ALWAYS);
        setBody(body);

        btnContinue = new JFXButton("Continuar");
        btnContinue.getStyleClass().add("dialog-accept");
        btnContinue.setDisable(true);
        btnContinue.setOnAction(e -> submitCode());

        continueSpinner = new SpinnerPane();
        continueSpinner.getStyleClass().add("small-spinner-pane");
        continueSpinner.setContent(btnContinue);

        btnCancel = new JFXButton("Cancelar");
        btnCancel.getStyleClass().add("dialog-cancel");
        btnCancel.setOnAction(e -> onCancel());

        setActions(continueSpinner, btnCancel);

        // Enable Continue once the user has typed/pasted something AND the browser was opened.
        pasteField.textProperty().addListener((obs, oldV, newV) ->
                btnContinue.setDisable(!browserOpened || newV == null || newV.trim().isEmpty()));

        onEscPressed(this, this::onCancel);
    }

    private void openBrowser() {
        String url = LegacyMicrosoftAuth.buildAuthorizeUrl();
        try {
            FXUtils.openLink(url);
        } catch (Exception e) {
            LOG.warning("Failed to open browser for legacy Microsoft login", e);
        }
        browserOpened = true;
        pasteField.setDisable(false);
        pasteField.requestFocus();
        statusLabel.setText("Navegador abierto. Cuando termines, pega aquí la URL y pulsa Continuar.");
        btnContinue.setDisable(pasteField.getText() == null || pasteField.getText().trim().isEmpty());
    }

    private void submitCode() {
        String code = LegacyMicrosoftAuth.extractCode(pasteField.getText());
        if (code == null) {
            statusLabel.setText("No se ha encontrado un código en lo que has pegado. Copia la URL completa de la barra de direcciones del navegador (debe contener \"?code=\").");
            return;
        }

        setBusy(true);
        statusLabel.setText("Iniciando sesión...");

        loginTask = Task.supplyAsync(() -> {
            // 1. Exchange auth code for access + refresh token
            LegacyMicrosoftAuth.TokenResponse token = LegacyMicrosoftAuth.exchangeCodeForToken(code);
            if (token.error != null) {
                throw new RuntimeException("Microsoft devolvió un error: " + token.error
                        + (token.errorDescription != null ? " — " + token.errorDescription : ""));
            }
            if (token.accessToken == null) {
                throw new RuntimeException("Microsoft no devolvió un access_token");
            }
            // 2. Run Xbox Live → XSTS → Minecraft chain with MBI_SSL prefix (t=)
            MicrosoftSession session = service.authenticateWithToken(
                    token.accessToken, token.refreshToken, true);
            // 3. Build account
            return service.createAccountFromSession(session);
        }).whenComplete(Schedulers.javafx(), this::onLoginCompleted).executor(true);
    }

    private void onLoginCompleted(MicrosoftAccount account, Exception exception) {
        setBusy(false);
        if (exception == null) {
            int oldIndex = Accounts.getAccounts().indexOf(account);
            if (oldIndex == -1) {
                Accounts.getAccounts().add(account);
            } else {
                Accounts.getAccounts().remove(oldIndex);
                Accounts.getAccounts().add(oldIndex, account);
            }
            Accounts.setSelectedAccount(account);
            fireEvent(new DialogCloseEvent());
            return;
        }
        if (exception instanceof CancellationException) {
            return;
        }
        LOG.warning("Legacy Microsoft login failed", exception);
        statusLabel.setText("Error: " + Accounts.localizeErrorMessage(
                exception instanceof Exception ? (Exception) exception : new RuntimeException(exception)));
    }

    private void setBusy(boolean busy) {
        btnOpenBrowser.setDisable(busy);
        btnCancel.setDisable(busy);
        pasteField.setDisable(busy);
        continueSpinner.setLoading(busy);
    }

    private void onCancel() {
        if (loginTask != null) loginTask.cancel();
        fireEvent(new DialogCloseEvent());
    }

    @Override
    public void onDialogShown() {
        Platform.runLater(btnOpenBrowser::requestFocus);
    }
}
