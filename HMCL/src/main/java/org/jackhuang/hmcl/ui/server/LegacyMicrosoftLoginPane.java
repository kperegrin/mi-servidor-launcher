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
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftService;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftSession;
import org.jackhuang.hmcl.server.LegacyMicrosoftAuth;
import org.jackhuang.hmcl.server.LocalOAuthCallbackServer;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Microsoft login using a localhost OAuth callback — fully automatic.
///
/// Flow:
///   1. User clicks "Iniciar sesión con Microsoft".
///   2. A free port is reserved on 127.0.0.1 and a background thread listens.
///   3. The system browser opens with the OAuth URL pointing at that local server.
///   4. User logs in.  Microsoft redirects the browser to 127.0.0.1:PORT/callback?code=...
///   5. The launcher catches the code and exchanges it for tokens — no copy/paste needed.
///
/// Falls back to a manual paste field if the local server cannot be started.
public final class LegacyMicrosoftLoginPane extends JFXDialogLayout implements DialogAware {

    private final MicrosoftService service = new MicrosoftService(Accounts.OAUTH_CALLBACK);

    private final Account accountToRelogin;
    private final Consumer<AuthInfo> loginCallback;
    private final Runnable cancelCallback;

    private final JFXButton btnOpenBrowser;
    private final JFXButton btnCancel;
    private final Label statusLabel;
    private final SpinnerPane continueSpinner;

    private LocalOAuthCallbackServer callbackServer;
    private String usedRedirectUri;
    private TaskExecutor loginTask;
    private boolean submitInFlight = false;

    // -------------------------------------------------------------------------
    // Constructors — same shapes as MicrosoftAccountLoginPane for drop-in use
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
        setMaxWidth(560);

        Label desc = new Label(
                "Pulsa el botón y el navegador se abrirá automáticamente. " +
                "Inicia sesión con tu cuenta de Microsoft y el launcher " +
                "completará el proceso solo — no tienes que copiar nada.");
        desc.setWrapText(true);
        desc.setStyle("-fx-text-fill: rgba(215,225,255,0.75); -fx-font-size: 13px;");

        btnOpenBrowser = new JFXButton("Iniciar sesión con Microsoft");
        btnOpenBrowser.getStyleClass().add("dialog-accept");
        btnOpenBrowser.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
        btnOpenBrowser.setMaxWidth(Double.MAX_VALUE);
        btnOpenBrowser.setOnAction(e -> openBrowser());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("server-progress-label");

        VBox body = new VBox(14, desc, btnOpenBrowser, statusLabel);
        body.setAlignment(Pos.TOP_LEFT);
        setBody(body);

        // Actions row: [spinner] [Cancelar]
        continueSpinner = new SpinnerPane();
        continueSpinner.getStyleClass().add("small-spinner-pane");

        btnCancel = new JFXButton("Cancelar");
        btnCancel.getStyleClass().add("dialog-cancel");
        btnCancel.setOnAction(e -> onCancel());

        setActions(continueSpinner, btnCancel);
        onEscPressed(this, this::onCancel);
    }

    // -------------------------------------------------------------------------
    // Browser flow
    // -------------------------------------------------------------------------

    private void openBrowser() {
        if (submitInFlight) return;

        // Try to start the local callback server.
        try {
            callbackServer = new LocalOAuthCallbackServer();
            usedRedirectUri = callbackServer.getRedirectUri();
        } catch (IOException e) {
            LOG.warning("Could not start local OAuth callback server — this should not happen", e);
            statusLabel.setText("Error interno: no se pudo iniciar el servidor local (" + e.getMessage() + ").");
            return;
        }

        String authUrl = LegacyMicrosoftAuth.buildAuthorizeUrlWithRedirect(usedRedirectUri);
        try {
            FXUtils.openLink(authUrl);
        } catch (Exception e) {
            LOG.warning("Failed to open browser for Microsoft login", e);
            statusLabel.setText("No se pudo abrir el navegador automáticamente. URL: " + authUrl);
        }

        btnOpenBrowser.setDisable(true);
        statusLabel.setText("Esperando que inicies sesión en el navegador…");
        continueSpinner.setLoading(true);

        // When the callback server receives the code, complete the login.
        callbackServer.getCodeFuture().whenComplete((code, throwable) ->
                Platform.runLater(() -> {
                    if (throwable != null) {
                        if (throwable instanceof CancellationException) return; // dialog was cancelled
                        handleLoginError(throwable instanceof Exception
                                ? (Exception) throwable
                                : new RuntimeException(throwable));
                        return;
                    }
                    statusLabel.setText("Código recibido. Completando inicio de sesión…");
                    exchangeCode(code);
                }));
    }

    // -------------------------------------------------------------------------
    // Token exchange & account creation
    // -------------------------------------------------------------------------

    private void exchangeCode(String code) {
        if (submitInFlight) return;
        submitInFlight = true;

        final String redirectUri = usedRedirectUri;

        loginTask = Task.supplyAsync(() -> {
            LegacyMicrosoftAuth.TokenResponse token =
                    LegacyMicrosoftAuth.exchangeCodeForTokenWithRedirect(code, redirectUri);

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

        handleLoginError(exception);
    }

    private void handleLoginError(Throwable e) {
        continueSpinner.setLoading(false);
        LOG.warning("Microsoft login failed", e);
        statusLabel.setText("Error: " + Accounts.localizeErrorMessage(
                e instanceof Exception ? (Exception) e : new RuntimeException(e)));
        btnOpenBrowser.setDisable(false);
        // Close old server so port is freed; a retry starts a fresh one.
        if (callbackServer != null) {
            callbackServer.close();
            callbackServer = null;
        }
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    private void onCancel() {
        if (callbackServer != null) {
            callbackServer.close();
            callbackServer = null;
        }
        if (loginTask != null) loginTask.cancel();
        if (cancelCallback != null) cancelCallback.run();
        fireEvent(new DialogCloseEvent());
    }

    @Override
    public void onDialogShown() {
        Platform.runLater(btnOpenBrowser::requestFocus);
    }
}
