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
import com.jfoenix.controls.JFXTextArea;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftService;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftSession;
import org.jackhuang.hmcl.server.LegacyMicrosoftAuth;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Microsoft login using the official Minecraft launcher's public credentials
/// (client ID 00000000402b5328 + login.live.com/oauth20_desktop.srf redirect).
///
/// Because the redirect URI isn't on localhost, we can't auto-capture the
/// callback. The user opens the browser, copies the URL of the blank
/// `oauth20_desktop.srf` page (which has `?code=...`), and pastes it back.
///
/// Supports the same constructor shapes as `MicrosoftAccountLoginPane` so all
/// call sites in HMCL (initial login, re-login, embedded in CreateAccountPane)
/// can use it as a drop-in replacement.
public final class LegacyMicrosoftLoginPane extends JFXDialogLayout implements DialogAware {

    // MicrosoftService is constructed locally; the OAuth callback it carries is
    // only used by the OAuth flow we bypass, so a stub is safe.
    private final MicrosoftService service = new MicrosoftService(Accounts.OAUTH_CALLBACK);

    private final Account accountToRelogin;
    private final Consumer<AuthInfo> loginCallback;
    private final Runnable cancelCallback;

    private final JFXButton btnOpenBrowser;
    private final JFXButton btnContinue;
    private final JFXButton btnCancel;
    private final JFXTextArea pasteField;
    private final Label statusLabel;
    private final SpinnerPane continueSpinner;

    private TaskExecutor loginTask;
    private Timeline clipboardPoller;
    private String lastClipboardContent = null;
    private boolean browserOpened = false;
    private boolean submitInFlight = false;

    public LegacyMicrosoftLoginPane() {
        this(null, null, null, false);
    }

    public LegacyMicrosoftLoginPane(boolean bodyonly) {
        this(null, null, null, bodyonly);
    }

    public LegacyMicrosoftLoginPane(Account account, Consumer<AuthInfo> callback, Runnable onCancel, boolean bodyonly) {
        this.accountToRelogin = account;
        this.loginCallback = callback;
        this.cancelCallback = onCancel;

        getStyleClass().add("microsoft-login-dialog");
        if (bodyonly) {
            this.pseudoClassStateChanged(PseudoClass.getPseudoClass("bodyonly"), true);
        } else {
            String headingText = accountToRelogin != null ? "Refrescar cuenta Microsoft" : "Añadir una cuenta Microsoft";
            Label heading = new Label(headingText);
            heading.getStyleClass().add("header-label");
            setHeading(heading);
        }
        this.setMaxWidth(650);

        Label step1 = new Label("1. Pulsa el botón y se abrirá tu navegador para iniciar sesión.");
        step1.setWrapText(true);
        Label step2 = new Label("2. Después de iniciar sesión verás una página en blanco. Pulsa Ctrl+L y luego Ctrl+C en el navegador para copiar la URL.");
        step2.setWrapText(true);
        Label step3 = new Label("3. El launcher detectará la URL automáticamente desde el portapapeles. Si no, pégala manualmente abajo.");
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

        VBox body = new VBox(10, step1, step2, step3, btnOpenBrowser, pasteField, statusLabel);
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

        pasteField.textProperty().addListener((obs, oldV, newV) ->
                btnContinue.setDisable(!browserOpened || newV == null || newV.trim().isEmpty()));

        onEscPressed(this, this::onCancel);
    }

    private void openBrowser() {
        String url = LegacyMicrosoftAuth.buildAuthorizeUrl();
        try {
            org.jackhuang.hmcl.ui.FXUtils.openLink(url);
        } catch (Exception e) {
            LOG.warning("Failed to open browser for legacy Microsoft login", e);
        }
        browserOpened = true;
        pasteField.setDisable(false);
        statusLabel.setText("Esperando a que copies la URL desde el navegador... (Ctrl+L → Ctrl+C). Se detectará automáticamente.");
        btnContinue.setDisable(pasteField.getText() == null || pasteField.getText().trim().isEmpty());
        // Snapshot current clipboard so we don't immediately react to whatever
        // the user happened to have copied before clicking the button.
        try {
            lastClipboardContent = Clipboard.getSystemClipboard().getString();
        } catch (Exception ignored) {
        }
        startClipboardPolling();
    }

    /// Polls the system clipboard every second looking for a freshly copied
    /// `oauth20_desktop.srf?code=...` URL. When found, auto-fills the paste
    /// field and submits, so the user never has to switch back to the launcher.
    /// Stops after 5 minutes or when login succeeds/cancels.
    private void startClipboardPolling() {
        stopClipboardPolling();
        clipboardPoller = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickClipboard()));
        clipboardPoller.setCycleCount(300); // ~5 min
        clipboardPoller.play();
    }

    private void tickClipboard() {
        if (submitInFlight) return;
        try {
            String content = Clipboard.getSystemClipboard().getString();
            if (content == null) return;
            String trimmed = content.trim();
            // Only react to NEW clipboard content (not whatever was there before).
            if (trimmed.equals(lastClipboardContent)) return;
            lastClipboardContent = trimmed;
            if (trimmed.contains("oauth20_desktop.srf") && trimmed.contains("code=")) {
                pasteField.setText(trimmed);
                // Immediately wipe the clipboard so the auth code is not
                // accessible to other apps after we've captured it.
                try {
                    Clipboard.getSystemClipboard().clear();
                } catch (Exception ignored) {
                }
                statusLabel.setText("URL detectada y borrada del portapapeles. Iniciando sesión...");
                stopClipboardPolling();
                submitCode();
            }
        } catch (Exception ignored) {
            // Some platforms throw if the clipboard owner crashes etc — just retry next tick.
        }
    }

    private void stopClipboardPolling() {
        if (clipboardPoller != null) {
            clipboardPoller.stop();
            clipboardPoller = null;
        }
    }

    private void submitCode() {
        String code = LegacyMicrosoftAuth.extractCode(pasteField.getText());
        if (code == null) {
            statusLabel.setText("No se ha encontrado un código en lo que has pegado. Copia la URL completa de la barra de direcciones del navegador (debe contener \"?code=\").");
            return;
        }

        submitInFlight = true;
        stopClipboardPolling();
        setBusy(true);
        statusLabel.setText("Iniciando sesión...");

        loginTask = Task.supplyAsync(() -> {
            LegacyMicrosoftAuth.TokenResponse token = LegacyMicrosoftAuth.exchangeCodeForToken(code);
            if (token.error != null) {
                throw new RuntimeException("Microsoft devolvió un error: " + token.error
                        + (token.errorDescription != null ? " — " + token.errorDescription : ""));
            }
            if (token.accessToken == null) {
                throw new RuntimeException("Microsoft no devolvió un access_token");
            }
            MicrosoftSession session = service.authenticateWithToken(
                    token.accessToken, token.refreshToken, true);
            return service.createAccountFromSession(session);
        }).whenComplete(Schedulers.javafx(), this::onLoginCompleted).executor(true);
    }

    private void onLoginCompleted(MicrosoftAccount account, Exception exception) {
        setBusy(false);
        submitInFlight = false;
        if (exception == null) {
            if (accountToRelogin != null) Accounts.getAccounts().remove(accountToRelogin);

            int oldIndex = Accounts.getAccounts().indexOf(account);
            if (oldIndex == -1) {
                Accounts.getAccounts().add(account);
            } else {
                Accounts.getAccounts().remove(oldIndex);
                Accounts.getAccounts().add(oldIndex, account);
            }
            Accounts.setSelectedAccount(account);

            if (loginCallback != null) {
                try {
                    loginCallback.accept(account.logIn());
                } catch (AuthenticationException e) {
                    statusLabel.setText("Error: " + Accounts.localizeErrorMessage(e));
                    return;
                }
            }
            fireEvent(new DialogCloseEvent());
            return;
        }
        if (exception instanceof CancellationException) {
            return;
        }
        LOG.warning("Legacy Microsoft login failed", exception);
        statusLabel.setText("Error: " + Accounts.localizeErrorMessage(
                exception instanceof Exception ? (Exception) exception : new RuntimeException(exception)));
        // Allow retry: resume clipboard watching so the user can try again with a new code.
        if (browserOpened) startClipboardPolling();
    }

    private void setBusy(boolean busy) {
        btnOpenBrowser.setDisable(busy);
        btnCancel.setDisable(busy);
        pasteField.setDisable(busy);
        continueSpinner.setLoading(busy);
    }

    private void onCancel() {
        stopClipboardPolling();
        if (loginTask != null) loginTask.cancel();
        if (cancelCallback != null) cancelCallback.run();
        fireEvent(new DialogCloseEvent());
    }

    @Override
    public void onDialogShown() {
        Platform.runLater(btnOpenBrowser::requestFocus);
    }
}
