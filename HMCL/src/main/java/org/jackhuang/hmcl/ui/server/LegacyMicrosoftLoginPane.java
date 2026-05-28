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
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
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
import org.jackhuang.hmcl.ui.FXUtils;
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
/// Flow:
///   1. User clicks the button → browser opens → user logs in.
///   2. Microsoft redirects to a blank page whose URL bar contains ?code=...
///   3. The launcher monitors the clipboard every second.
///   4. As soon as the user presses Ctrl+L (focus URL bar) and Ctrl+C
///      the full URL lands in the clipboard and the launcher captures it
///      automatically — no pasting needed.
///   5. The code is exchanged for tokens and the account is created.
public final class LegacyMicrosoftLoginPane extends JFXDialogLayout implements DialogAware {

    private final MicrosoftService service = new MicrosoftService(Accounts.OAUTH_CALLBACK);

    private final Account accountToRelogin;
    private final Consumer<AuthInfo> loginCallback;
    private final Runnable cancelCallback;

    private final JFXButton btnOpenBrowser;
    private final JFXButton btnCancel;
    private final Label statusLabel;
    private final SpinnerPane continueSpinner;

    private TaskExecutor loginTask;
    private Timeline clipboardPoller;
    private String lastClipboardContent = null;
    private boolean browserOpened = false;
    private boolean submitInFlight = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public LegacyMicrosoftLoginPane() {
        this(null, null, null, false);
    }

    public LegacyMicrosoftLoginPane(boolean bodyonly) {
        this(null, null, null, bodyonly);
    }

    public LegacyMicrosoftLoginPane(Account account, Consumer<AuthInfo> callback,
                                     Runnable onCancel, boolean bodyonly) {
        this.accountToRelogin = account;
        this.loginCallback    = callback;
        this.cancelCallback   = onCancel;

        getStyleClass().add("microsoft-login-dialog");
        if (bodyonly) {
            pseudoClassStateChanged(PseudoClass.getPseudoClass("bodyonly"), true);
        } else {
            String heading = accountToRelogin != null
                    ? "Refrescar cuenta Microsoft"
                    : "Añadir una cuenta Microsoft";
            Label headingLabel = new Label(heading);
            headingLabel.getStyleClass().add("header-label");
            setHeading(headingLabel);
        }
        setMaxWidth(580);

        // Step-by-step instructions
        Label step1 = new Label("1.  Pulsa el botón — el navegador se abrirá.");
        Label step2 = new Label("2.  Inicia sesión con tu cuenta de Microsoft.");
        Label step3 = new Label("3.  Verás una página en blanco. Pulsa  Ctrl + L  y luego  Ctrl + C  para copiar la URL.");
        Label step4 = new Label("    El launcher detectará el código solo y completará el inicio de sesión.");

        for (Label lbl : new Label[]{step1, step2, step3, step4}) {
            lbl.setWrapText(true);
            lbl.setStyle("-fx-text-fill: rgba(215,225,255,0.80); -fx-font-size: 13px;");
        }
        step3.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;");

        btnOpenBrowser = new JFXButton("Iniciar sesión con Microsoft");
        btnOpenBrowser.getStyleClass().add("dialog-accept");
        btnOpenBrowser.setStyle("-fx-text-fill: white;");
        btnOpenBrowser.setMaxWidth(Double.MAX_VALUE);
        btnOpenBrowser.setOnAction(e -> openBrowser());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("server-progress-label");
        statusLabel.setPadding(new Insets(4, 0, 0, 0));

        VBox body = new VBox(10, step1, step2, step3, step4, btnOpenBrowser, statusLabel);
        body.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(btnOpenBrowser, Priority.NEVER);
        setBody(body);

        continueSpinner = new SpinnerPane();
        continueSpinner.getStyleClass().add("small-spinner-pane");

        btnCancel = new JFXButton("Cancelar");
        btnCancel.getStyleClass().add("dialog-cancel");
        btnCancel.setOnAction(e -> onCancel());

        setActions(continueSpinner, btnCancel);
        onEscPressed(this, this::onCancel);
    }

    // -------------------------------------------------------------------------
    // Browser flow + clipboard monitoring
    // -------------------------------------------------------------------------

    private void openBrowser() {
        String url = LegacyMicrosoftAuth.buildAuthorizeUrl();
        try {
            FXUtils.openLink(url);
        } catch (Exception e) {
            LOG.warning("Failed to open browser for Microsoft login", e);
        }
        browserOpened = true;
        btnOpenBrowser.setDisable(true);
        statusLabel.setText("Esperando código…  Cuando veas la página en blanco pulsa  Ctrl+L  →  Ctrl+C.");

        // Snapshot current clipboard so we don't react to pre-existing content.
        try {
            lastClipboardContent = Clipboard.getSystemClipboard().getString();
        } catch (Exception ignored) {
        }
        startClipboardPolling();
    }

    /// Polls every second for a freshly-copied oauth20_desktop.srf URL.
    /// Stops after 5 min or when login completes/cancels.
    private void startClipboardPolling() {
        stopClipboardPolling();
        clipboardPoller = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickClipboard()));
        clipboardPoller.setCycleCount(300);
        clipboardPoller.play();
    }

    private void tickClipboard() {
        if (submitInFlight) return;
        try {
            String content = Clipboard.getSystemClipboard().getString();
            if (content == null) return;
            String trimmed = content.trim();
            if (trimmed.equals(lastClipboardContent)) return;
            lastClipboardContent = trimmed;

            if (trimmed.contains("oauth20_desktop.srf") && trimmed.contains("code=")) {
                // Wipe clipboard immediately so auth code is not left behind.
                try { Clipboard.getSystemClipboard().clear(); } catch (Exception ignored) {}
                statusLabel.setText("Código detectado. Iniciando sesión…");
                stopClipboardPolling();
                String code = LegacyMicrosoftAuth.extractCode(trimmed);
                if (code == null) {
                    statusLabel.setText("URL encontrada pero no contiene un código válido. Vuelve a intentarlo.");
                    btnOpenBrowser.setDisable(false);
                    return;
                }
                exchangeCode(code);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopClipboardPolling() {
        if (clipboardPoller != null) {
            clipboardPoller.stop();
            clipboardPoller = null;
        }
    }

    // -------------------------------------------------------------------------
    // Token exchange & account creation
    // -------------------------------------------------------------------------

    private void exchangeCode(String code) {
        submitInFlight = true;
        continueSpinner.setLoading(true);

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
        continueSpinner.setLoading(false);
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

        if (exception instanceof CancellationException) return;

        LOG.warning("Legacy Microsoft login failed", exception);
        statusLabel.setText("Error: " + Accounts.localizeErrorMessage(
                exception instanceof Exception ? exception : new RuntimeException(exception)));
        btnOpenBrowser.setDisable(false);
        if (browserOpened) startClipboardPolling();
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

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
