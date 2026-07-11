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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.server.CardCollectionService;
import org.jackhuang.hmcl.server.CardCollectionService.Card;
import org.jackhuang.hmcl.server.CardCollectionService.Inventory;
import org.jackhuang.hmcl.server.CardImageCache;
import org.jackhuang.hmcl.server.CardCollectionService.Rarity;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Full sidebar page hosting the card chest: 12h claim countdown, claim button, fusion (3→1)
/// and a live inventory grid with duplicate badges. Reads/writes through {@link CardCollectionService}.
@NotNullByDefault
public final class CardsPage extends DecoratorAnimatedPage implements DecoratorPage {

    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle("Cofre de cartas"));

    private final @Nullable String userName;
    private final FlowPane inventoryGrid = new FlowPane(14, 14);
    private final Label statusLabel = new Label();
    private final Label countdownLabel = new Label();
    private final Label countdownCaption = new Label();
    private final JFXButton claimButton = new JFXButton("Abrir cofre");
    private final JFXButton fuseButton = new JFXButton("Fusionar (0/3)");
    private final JFXButton refreshButton = new JFXButton("Refrescar catálogo");
    private final JFXButton pokedexButton = new JFXButton("Pokédex");

    /// Cards picked for the next fusion (insertion order, ids may repeat).
    private final List<String> fusionSelection = new ArrayList<>();

    /// Already-built tile nodes, keyed by card id. The incremental renderer reuses these
    /// instead of clearing + rebuilding the FlowPane every refresh — that's what was making
    /// the grid blink and the images reload each time the user clicked on a card.
    private final Map<String, Region> tileCache = new HashMap<>();
    /// Last-rendered "state" per card id ({@code count:picked}). When this signature changes the
    /// tile is rebuilt; otherwise the cached node is reused verbatim.
    private final Map<String, String> tileStateCache = new HashMap<>();

    private Timeline countdown;
    private boolean busy = false;

    public CardsPage() {
        Account account = Accounts.getSelectedAccount();
        this.userName = account == null ? null : account.getCharacter();

        getStyleClass().add("server-root-bg");

        buildLayout();

        // Live countdown ticks while the page is on a scene; stop on detach.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                stopCountdown();
            } else {
                refreshPage();
                startCountdown();
            }
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    private void buildLayout() {
        // ----- Header card: countdown + claim button + actions -----
        countdownCaption.setText(userName == null ? "Sin cuenta seleccionada" : "Tiradas disponibles · proxima en:");
        countdownCaption.getStyleClass().add("server-progress-label");
        countdownLabel.setText("—");
        countdownLabel.getStyleClass().add("server-card-countdown");
        VBox countdownBox = new VBox(2, countdownCaption, countdownLabel);

        claimButton.getStyleClass().addAll("server-primary-button", "dialog-accept");
        claimButton.setStyle("-fx-text-fill: white;");
        claimButton.setOnAction(e -> doClaim());

        fuseButton.getStyleClass().add("server-secondary-button");
        fuseButton.setDisable(true);
        fuseButton.setOnAction(e -> doFuse());

        refreshButton.getStyleClass().add("server-secondary-button");
        refreshButton.setOnAction(e -> {
            CardCollectionService.invalidateCatalog();
            refreshPage();
            statusLabel.setText("Catálogo actualizado desde el servidor.");
        });
        FXUtils.installFastTooltip(refreshButton,
                "Vuelve a leer el catálogo de cartas y tu inventario desde el servidor.");

        pokedexButton.getStyleClass().add("server-secondary-button");
        pokedexButton.setOnAction(e -> {
            if (userName == null) {
                statusLabel.setText("Inicia sesión para ver tu Pokédex.");
                return;
            }
            Controllers.dialog(new PokedexDialog(userName));
        });
        FXUtils.installFastTooltip(pokedexButton,
                "Muestra todas las cartas obtenibles. Las que tienes (o has tenido) salen a color; las demás aparecen en silueta.");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(14, countdownBox, spacer, pokedexButton, refreshButton, fuseButton, claimButton);
        topBar.setAlignment(Pos.CENTER_LEFT);

        VBox headerCard = new VBox(8, topBar);
        headerCard.getStyleClass().add("server-home");
        headerCard.setPadding(new Insets(16));

        // Admin row (whitelist only): regalar cofre a todos + reiniciar mi inventario.
        if (userName != null && CardCollectionService.hasUnlimitedClaims(userName)) {
            headerCard.getChildren().add(buildAdminBar());
        }

        // ----- Inventory grid -----
        inventoryGrid.setPadding(new Insets(4));
        inventoryGrid.setAlignment(Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(inventoryGrid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("server-news-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox inventoryCard = new VBox(8, sectionLabel("Tu colección"), scroll);
        inventoryCard.getStyleClass().add("server-home");
        inventoryCard.setPadding(new Insets(16));
        VBox.setVgrow(inventoryCard, Priority.ALWAYS);

        // ----- Status footer -----
        statusLabel.getStyleClass().add("server-progress-label");
        statusLabel.setText(userName == null
                ? "Inicia sesión con una cuenta para reclamar y coleccionar cartas."
                : "Pulsa una carta para seleccionarla para una fusión. 3 cartas cualesquiera → 1 nueva al azar.");

        VBox root = new VBox(16, headerCard, inventoryCard, statusLabel);
        root.setPadding(new Insets(24));
        root.setFillWidth(true);

        ScrollPane outer = new ScrollPane(root);
        outer.setFitToWidth(true);
        outer.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        outer.getStyleClass().add("server-home-scroll");
        outer.setStyle("-fx-background-color: transparent;");

        setCenter(outer);
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("server-news-title");
        return label;
    }

    /// Admin row with the two whitelist-only buttons: "Regalar cofre a todos" (publishes a gift
    /// in Firebase so every player gets +1 ticket on their next chest refresh) and "Reiniciar mi
    /// inventario" (wipes the current player's collection so the admin can test from scratch).
    private HBox buildAdminBar() {
        Label tag = new Label("Admin");
        tag.setStyle("-fx-text-fill: #ffaa22; -fx-font-weight: bold; -fx-font-size: 11px;"
                + " -fx-padding: 2 8 2 8; -fx-background-color: rgba(255,170,34,0.12);"
                + " -fx-background-radius: 8; -fx-border-color: rgba(255,170,34,0.45);"
                + " -fx-border-radius: 8;");

        JFXButton giftAllButton = new JFXButton("Regalar cofre a todos");
        giftAllButton.getStyleClass().add("server-secondary-button");
        FXUtils.installFastTooltip(giftAllButton,
                "Publica un regalo en el servidor. Cada jugador recibira +1 tirada la proxima vez que abra el cofre. No se repite: cada gift se aplica una sola vez por jugador.");
        giftAllButton.setOnAction(e -> doGiftAll());

        JFXButton resetButton = new JFXButton("Reiniciar mi inventario");
        resetButton.getStyleClass().add("server-secondary-button");
        FXUtils.installFastTooltip(resetButton,
                "Borra TODAS tus cartas, tu Pokedex y reinicia tus tiradas a cero. Solo afecta a tu cuenta. Util para testing.");
        resetButton.setOnAction(e -> doResetInventory());

        Region adminSpacer = new Region();
        HBox.setHgrow(adminSpacer, Priority.ALWAYS);
        HBox row = new HBox(10, tag, adminSpacer, resetButton, giftAllButton);
        row.setAlignment(Pos.CENTER_LEFT);

        // Botón SOLO para el master admin (ElKimiZG). Barrilmc, aunque sea VIP general, no lo
        // ve — no debería poder farmear infinitos.
        if (userName != null && CardCollectionService.isMasterAdmin(userName)) {
            JFXButton infinitoButton = new JFXButton("⚡ Juan el Pro");
            infinitoButton.getStyleClass().addAll("server-primary-button", "dialog-accept");
            infinitoButton.setStyle("-fx-text-fill: white;"
                    + " -fx-background-color: linear-gradient(135deg, #ff00ff, #aa00ff);"
                    + " -fx-effect: dropshadow(gaussian, rgba(255,0,255,0.6), 16, 0.3, 0, 2);");
            FXUtils.installFastTooltip(infinitoButton,
                    "Te da una carta INFINITO (Juan el Pro) saltándose el random. Solo ElKimiZG.");
            infinitoButton.setOnAction(e -> doForceJuanElPro());
            row.getChildren().add(infinitoButton);
        }

        return row;
    }

    private void doForceJuanElPro() {
        if (userName == null) return;
        if (!CardCollectionService.isMasterAdmin(userName)) {
            statusLabel.setText("Solo el master admin puede usar esto.");
            return;
        }
        new Thread(() -> {
            org.jackhuang.hmcl.server.CardCollectionService.Card got = null;
            String error = null;
            try {
                got = org.jackhuang.hmcl.server.CardCollectionService.claimSpecificCard(userName, "juan_el_pro");
            } catch (IOException ex) {
                error = ex.getMessage();
            }
            final org.jackhuang.hmcl.server.CardCollectionService.Card result = got;
            final String finalError = error;
            Platform.runLater(() -> {
                if (finalError != null) {
                    statusLabel.setText("No se pudo conseguir Juan el Pro: " + finalError);
                } else if (result == null) {
                    statusLabel.setText("La carta 'juan_el_pro' no esta en el catalogo todavia.");
                } else {
                    statusLabel.setText("¡Juan el Pro forzado! Disfruta la animacion.");
                    CardRevealOverlay.play(result, "Easter egg");
                    refreshPage();
                }
            });
        }, "barrilmc-force-juan").start();
    }

    private void doGiftAll() {
        if (userName == null) return;
        org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder builder =
                new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        "¿Regalar un cofre (+1 tirada) a TODOS los jugadores de BarrilMC? Es inmediato y publico — el regalo aparece en el cofre del resto la proxima vez que abran el launcher.",
                        "Regalar cofre a todos",
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
        builder.yesOrNo(() -> {
            statusLabel.setText("Enviando regalo…");
            new Thread(() -> {
                boolean ok = CardCollectionService.giveAllPlayersOneClaim(userName);
                Platform.runLater(() -> {
                    if (ok) {
                        statusLabel.setText("¡Regalo enviado! Todos los jugadores recibiran +1 tirada al abrir el cofre.");
                        // Aplica el regalo a uno mismo de inmediato para que se vea el efecto.
                        new Thread(() -> {
                            int applied = CardCollectionService.applyPendingGifts(userName);
                            Platform.runLater(() -> {
                                if (applied > 0) {
                                    refreshPage();
                                }
                            });
                        }, "barrilmc-self-gift").start();
                    } else {
                        statusLabel.setText("No se pudo enviar el regalo (sin conexion con el servidor).");
                    }
                });
            }, "barrilmc-gift-all").start();
        }, () -> {});
        Controllers.dialog(builder.build());
    }

    private void doResetInventory() {
        if (userName == null) return;
        org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder builder =
                new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        "¿Borrar TU inventario, tu Pokedex y resetear tus tiradas? Esta accion solo te afecta a ti y no se puede deshacer.",
                        "Reiniciar mi inventario",
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
        builder.yesOrNo(() -> {
            new Thread(() -> {
                String error = null;
                try {
                    CardCollectionService.resetInventory(userName);
                } catch (IOException e) {
                    error = e.getMessage();
                }
                final String finalError = error;
                Platform.runLater(() -> {
                    if (finalError != null) {
                        statusLabel.setText("No se pudo reiniciar: " + finalError);
                    } else {
                        statusLabel.setText("Inventario reiniciado.");
                        fusionSelection.clear();
                        tileCache.clear();
                        tileStateCache.clear();
                        refreshPage();
                    }
                });
            }, "barrilmc-reset-inventory").start();
        }, () -> {});
        Controllers.dialog(builder.build());
    }

    // ============================================================================================
    //                                Countdown + refresh loop
    // ============================================================================================

    private void startCountdown() {
        stopCountdown();
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickCountdown()));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    private void refreshPage() {
        // Background preload: download every catalogue PNG that isn't yet in the on-disk cache so
        // future claims and fusions reveal cards instantly instead of stalling on the network.
        // This call returns immediately; the actual downloads run on a small pool of daemon threads.
        try {
            CardImageCache.preloadAll(CardCollectionService.listAllCards());
        } catch (Exception ignored) {
        }
        // Pending gifts: en background, pide a Firebase los regalos que aun no he reclamado y
        // aplica +1 tirada por cada uno. Si hay alguno nuevo, refresca la UI para mostrar el
        // contador actualizado y un mensaje en la barra de estado.
        if (userName != null) {
            new Thread(() -> {
                int applied = CardCollectionService.applyPendingGifts(userName);
                if (applied > 0) {
                    Platform.runLater(() -> {
                        statusLabel.setText("¡Has recibido " + applied
                                + (applied == 1 ? " cofre regalo!" : " cofres regalo!"));
                        tickCountdown();
                    });
                }
            }, "barrilmc-pending-gifts").start();
        }
        renderInventory();
        tickCountdown();
        updateFuseButton();
    }

    private void tickCountdown() {
        if (userName == null) {
            countdownLabel.setText("Sin cuenta");
            claimButton.setText("Abrir cofre");
            claimButton.setDisable(true);
            return;
        }
        boolean unlimited = CardCollectionService.hasUnlimitedClaims(userName);
        int available = CardCollectionService.getAvailableClaims(userName);
        int max = CardCollectionService.MAX_PENDING_CLAIMS;

        // Texto del botón: "Abrir cofre (N)" con la cantidad de tiradas disponibles.
        if (unlimited) {
            claimButton.setText("Abrir cofre (VIP)");
        } else {
            claimButton.setText("Abrir cofre (" + available + ")");
        }
        claimButton.setDisable(busy || (available <= 0 && !unlimited));

        if (unlimited) {
            countdownLabel.setText("VIP · " + available + " / " + max + "  (sin esperar)");
            countdownLabel.setStyle("-fx-text-fill: #34d058;");
            return;
        }

        long remaining = CardCollectionService.timeUntilNextClaim(userName);
        int perTick = CardCollectionService.CLAIMS_PER_TICK;
        if (available >= max) {
            countdownLabel.setText(available + " / " + max + " · maximo alcanzado");
            countdownLabel.setStyle("-fx-text-fill: #34d058;");
        } else if (remaining <= 0) {
            countdownLabel.setText(available + " / " + max + " · 00:00:00");
            countdownLabel.setStyle("-fx-text-fill: #ffaa22;");
        } else {
            long hours = remaining / 3_600_000L;
            long minutes = (remaining / 60_000L) % 60;
            long seconds = (remaining / 1_000L) % 60;
            countdownLabel.setText(String.format("%d / %d · +%d en %02d:%02d:%02d",
                    available, max, perTick, hours, minutes, seconds));
            countdownLabel.setStyle("-fx-text-fill: " + (available > 0 ? "#34d058" : "#ffaa22") + ";");
        }
    }

    // ============================================================================================
    //                                       Inventory grid
    // ============================================================================================

    private void renderInventory() {
        if (userName == null) {
            tileCache.clear();
            tileStateCache.clear();
            inventoryGrid.getChildren().setAll(placeholder(
                    "Inicia sesión con una cuenta para empezar tu colección."));
            return;
        }

        Inventory inv = CardCollectionService.loadInventory(userName);
        if (inv.getCounts().isEmpty()) {
            tileCache.clear();
            tileStateCache.clear();
            List<Card> all = CardCollectionService.listAllCards();
            String msg = all.isEmpty()
                    ? "Todavía no hay cartas disponibles."
                    : "Aun no tienes cartas. Pulsa \"Abrir cofre diario\" para empezar.";
            inventoryGrid.getChildren().setAll(placeholder(msg));
            return;
        }

        // Sort the inventory entries by rarity (rarest first) and then by card name. This way
        // legendarias appear at the top of the grid, then épicas, raras y comunes al final.
        java.util.List<Map.Entry<String, Integer>> sorted = new ArrayList<>(inv.getCounts().entrySet());
        sorted.sort((a, b) -> {
            Card ca = CardCollectionService.findCard(a.getKey());
            Card cb = CardCollectionService.findCard(b.getKey());
            // Unknown cards (no longer in the catalogue) go to the end so they don't break the order.
            if (ca == null && cb == null) return 0;
            if (ca == null) return 1;
            if (cb == null) return -1;
            int byRarity = Integer.compare(cb.getRarity().ordinal(), ca.getRarity().ordinal());
            if (byRarity != 0) return byRarity;
            return ca.getName().compareToIgnoreCase(cb.getName());
        });

        // Incremental render: reuse tile nodes whose state hasn't changed, only rebuild the ones
        // whose count or fusion-selection mark actually changed. This avoids the whole grid
        // blinking every time the user clicks a card or claims a new one, and the image cache
        // ensures the art never reloads.
        Set<String> stillPresent = new HashSet<>();
        LinkedHashMap<String, Region> orderedTiles = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sorted) {
            Card card = CardCollectionService.findCard(entry.getKey());
            if (card == null) {
                continue; // catalogue doesn't know this card any more; skip silently
            }
            int count = entry.getValue();
            int picked = (int) fusionSelection.stream().filter(id -> id.equals(card.getId())).count();
            String signature = count + ":" + picked;
            stillPresent.add(card.getId());

            Region tile = tileCache.get(card.getId());
            String prevState = tileStateCache.get(card.getId());
            if (tile == null || !signature.equals(prevState)) {
                tile = cardTile(card, count);
                tileCache.put(card.getId(), tile);
                tileStateCache.put(card.getId(), signature);
            }
            orderedTiles.put(card.getId(), tile);
        }

        // Drop tiles for cards that aren't owned any more (fully fused away, etc.).
        Iterator<Map.Entry<String, Region>> it = tileCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Region> e = it.next();
            if (!stillPresent.contains(e.getKey())) {
                tileStateCache.remove(e.getKey());
                it.remove();
            }
        }

        // setAll() is a no-op when the list is identical, and otherwise reorders without
        // recreating nodes — so cached tiles keep their existing layout/image untouched.
        inventoryGrid.getChildren().setAll(orderedTiles.values());
    }

    private Region cardTile(Card card, int count) {
        double w = 144;
        double h = 200;

        ImageView art = new ImageView();
        try {
            // Shared image from the cache: instant from disk/memory on subsequent renders,
            // never re-downloads while the launcher is open.
            art.setImage(CardImageCache.getImage(card));
        } catch (RuntimeException ignored) {
        }
        art.setFitWidth(w - 16);
        art.setFitHeight(h - 64);
        art.setPreserveRatio(true);
        art.setSmooth(true);

        Label name = new Label(card.getName());
        name.getStyleClass().add("server-card-name");
        name.setWrapText(true);
        name.setMaxWidth(w - 16);
        name.setAlignment(Pos.CENTER);

        Label rarity = new Label(card.getRarity().getDisplayName());
        rarity.setStyle("-fx-text-fill: " + card.getRarity().getColor() + ";"
                + " -fx-font-size: 10px; -fx-font-weight: bold;");

        VBox content = new VBox(4, art, name, rarity);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(8));

        StackPane tile = new StackPane(content);
        tile.setMinSize(w, h);
        tile.setPrefSize(w, h);
        tile.setMaxSize(w, h);
        tile.getStyleClass().add("server-card-tile");
        tile.setStyle("-fx-border-color: " + card.getRarity().getColor() + ";"
                + " -fx-border-width: 2; -fx-border-radius: 10; -fx-background-radius: 10;"
                + " -fx-background-color: rgba(255,255,255,0.05);");

        if (count > 1) {
            Label badge = new Label("x" + count);
            badge.setStyle("-fx-background-color: " + card.getRarity().getColor() + ";"
                    + " -fx-text-fill: #101016; -fx-font-weight: bold; -fx-font-size: 11px;"
                    + " -fx-padding: 2 8 2 8; -fx-background-radius: 10;");
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            StackPane.setMargin(badge, new Insets(6, 6, 0, 0));
            tile.getChildren().add(badge);
        }

        int picked = (int) fusionSelection.stream().filter(id -> id.equals(card.getId())).count();
        if (picked > 0) {
            Label mark = new Label("✓ " + picked);
            mark.setStyle("-fx-background-color: rgba(52, 208, 88, 0.92);"
                    + " -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;"
                    + " -fx-padding: 2 8 2 8; -fx-background-radius: 10;");
            StackPane.setAlignment(mark, Pos.TOP_LEFT);
            StackPane.setMargin(mark, new Insets(6, 0, 0, 6));
            tile.getChildren().add(mark);
        }

        tile.setCursor(Cursor.HAND);
        tile.setOnMouseClicked(e -> openInspector(card, count));
        return tile;
    }

    /// Modal inspector for a card: big preview + "Cerrar" or "Usar para fusión" action. Fusion
    /// requires THREE cards in total — picking from here only marks this one for the next fuse.
    private void openInspector(Card card, int count) {
        if (userName == null) {
            return;
        }
        int alreadyPicked = (int) fusionSelection.stream().filter(id -> id.equals(card.getId())).count();
        boolean inSelection = alreadyPicked > 0;

        JFXDialogLayout dialog = new JFXDialogLayout();
        dialog.getStyleClass().add("microsoft-login-dialog");
        dialog.setPrefWidth(420);

        Label heading = new Label(card.getName());
        heading.getStyleClass().add("header-label");
        dialog.setHeading(heading);

        double w = 240, h = 336;
        ImageView art = new ImageView();
        try {
            art.setImage(CardImageCache.getImage(card));
        } catch (RuntimeException ignored) {
        }
        art.setFitWidth(w);
        art.setFitHeight(h);
        art.setPreserveRatio(true);
        art.setSmooth(true);

        StackPane frame = new StackPane(art);
        frame.setMinSize(w + 16, h + 16);
        frame.setMaxSize(w + 16, h + 16);
        frame.setStyle("-fx-border-color: " + card.getRarity().getColor() + ";"
                + " -fx-border-width: 3; -fx-border-radius: 12; -fx-background-radius: 12;"
                + " -fx-background-color: rgba(255,255,255,0.05);");

        Label rarity = new Label("Rareza: " + card.getRarity().getDisplayName());
        rarity.setStyle("-fx-text-fill: " + card.getRarity().getColor() + "; -fx-font-weight: bold;");

        Label countLabel = new Label("Tienes: " + count + (count == 1 ? " copia" : " copias"));
        countLabel.getStyleClass().add("server-progress-label");

        Label selLabel = new Label();
        selLabel.getStyleClass().add("server-progress-label");
        Rarity selectedRarity = selectedFusionRarity();
        Rarity resultRarity = card.getRarity().getFusionResultRarity();
        boolean sameFusionRarity = selectedRarity == null || selectedRarity == card.getRarity();
        if (inSelection) {
            selLabel.setText("Ya estás usando " + alreadyPicked
                    + (alreadyPicked == 1 ? " copia" : " copias") + " en la próxima fusión.");
            selLabel.setStyle("-fx-text-fill: #34d058;");
        } else {
            int picked = fusionSelection.size();
            selLabel.setText("Fusión actual: " + picked + "/" + CardCollectionService.FUSION_COST
                    + " cartas. 3 cualesquiera = 1 nueva (las 3 se pierden).");
        }

        if (!inSelection && !sameFusionRarity) {
            selLabel.setText("No puedes mezclar rarezas. La fusion actual es de "
                    + selectedRarity.getDisplayName().toLowerCase() + ".");
            selLabel.setStyle("-fx-text-fill: #ff6b6b;");
        } else if (!inSelection && resultRarity == null) {
            selLabel.setText("Las cartas leyenda no pueden subir mas de rareza.");
            selLabel.setStyle("-fx-text-fill: #ffaa22;");
        } else if (!inSelection) {
            int picked = fusionSelection.size();
            selLabel.setText("Fusion actual: " + picked + "/" + CardCollectionService.FUSION_COST
                    + " cartas. 3 " + card.getRarity().getDisplayName().toLowerCase()
                    + " = 1 " + resultRarity.getDisplayName().toLowerCase() + ".");
        }

        VBox body = new VBox(12, frame, rarity, countLabel, selLabel);
        body.setAlignment(Pos.CENTER);
        body.setPadding(new Insets(8, 4, 4, 4));
        dialog.setBody(body);

        JFXButton close = new JFXButton("Cerrar");
        close.getStyleClass().add("dialog-cancel");
        close.setOnAction(e -> dialog.fireEvent(new DialogCloseEvent()));

        // Remove-one-copy button (only enabled while you have copies in the fusion selection).
        JFXButton removeBtn = new JFXButton(inSelection
                ? "Quitar 1 de la fusión (" + alreadyPicked + " en uso)"
                : "Quitar 1 de la fusión");
        removeBtn.getStyleClass().add("server-secondary-button");
        removeBtn.setDisable(!inSelection);
        removeBtn.setOnAction(e -> {
            removeFusionPick(card.getId());
            dialog.fireEvent(new DialogCloseEvent());
        });

        // Add-one-more-copy button (enabled while you still own copies and fusion isn't full).
        int picked = fusionSelection.size();
        boolean roomLeft = picked < CardCollectionService.FUSION_COST;
        boolean canSelectMore = count > alreadyPicked;
        JFXButton addBtn = new JFXButton("Añadir 1 más a la fusión");
        addBtn.getStyleClass().addAll("server-primary-button", "dialog-accept");
        addBtn.setStyle("-fx-text-fill: white;");
        addBtn.setDisable(!roomLeft || !canSelectMore || !sameFusionRarity || resultRarity == null);
        if (!roomLeft) {
            addBtn.setText("Fusión llena (3/3)");
        } else if (!canSelectMore) {
            addBtn.setText("Sin copias disponibles");
        } else if (!sameFusionRarity) {
            addBtn.setText("No mezclar rarezas");
        } else if (resultRarity == null) {
            addBtn.setText("Rareza maxima");
        }
        addBtn.setOnAction(e -> {
            addFusionPick(card.getId());
            dialog.fireEvent(new DialogCloseEvent());
        });

        dialog.setActions(close, removeBtn, addBtn);
        Controllers.dialog(dialog);
    }

    private void addFusionPick(String cardId) {
        Card card = CardCollectionService.findCard(cardId);
        Rarity selected = selectedFusionRarity();
        if (card == null || card.getRarity().getFusionResultRarity() == null
                || (selected != null && selected != card.getRarity())) {
            statusLabel.setText("Solo puedes fusionar 3 cartas de la misma rareza.");
            return;
        }
        if (fusionSelection.size() < CardCollectionService.FUSION_COST) {
            fusionSelection.add(cardId);
            renderInventory();
            updateFuseButton();
            statusLabel.setText("Carta añadida a la fusión ("
                    + fusionSelection.size() + "/" + CardCollectionService.FUSION_COST + ").");
        }
    }

    private void removeFusionPick(String cardId) {
        if (fusionSelection.remove(cardId)) {
            renderInventory();
            updateFuseButton();
            statusLabel.setText("Carta quitada de la fusión ("
                    + fusionSelection.size() + "/" + CardCollectionService.FUSION_COST + ").");
        }
    }

    private void updateFuseButton() {
        int picked = fusionSelection.size();
        fuseButton.setText("Fusionar (" + picked + "/" + CardCollectionService.FUSION_COST + ")");
        fuseButton.setDisable(busy || userName == null || picked != CardCollectionService.FUSION_COST
                || selectedFusionRarity() == null);
    }

    private @Nullable Rarity selectedFusionRarity() {
        Rarity rarity = null;
        for (String id : fusionSelection) {
            Card card = CardCollectionService.findCard(id);
            if (card == null || card.getRarity().getFusionResultRarity() == null) {
                return null;
            }
            if (rarity == null) {
                rarity = card.getRarity();
            } else if (rarity != card.getRarity()) {
                return null;
            }
        }
        return rarity;
    }

    // ============================================================================================
    //                                       Claim / fuse
    // ============================================================================================

    private void doClaim() {
        if (busy || userName == null) {
            return;
        }
        busy = true;
        statusLabel.setText("Abriendo cofre diario...");
        new Thread(() -> {
            Card got = null;
            String error = null;
            try {
                got = CardCollectionService.claimDaily(userName);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final Card finalGot = got;
            final String finalError = error;
            Platform.runLater(() -> {
                busy = false;
                if (finalError != null) {
                    statusLabel.setText("No se pudo guardar el inventario: " + finalError);
                } else if (finalGot == null) {
                    statusLabel.setText("Todavía no toca. Vuelve cuando el contador llegue a 00:00:00.");
                } else {
                    statusLabel.setText("¡Has conseguido una carta " + finalGot.getRarity().getDisplayName().toLowerCase()
                            + "! → " + finalGot.getName());
                    CardRevealOverlay.play(finalGot, "Cofre diario abierto", this::refreshPage);
                }
                if (finalGot == null) {
                    refreshPage();
                }
            });
        }, "barrilmc-card-claim").start();
    }

    private void doFuse() {
        if (busy || userName == null || fusionSelection.size() != CardCollectionService.FUSION_COST) {
            return;
        }
        // Pre-check: si las 3 son leyendas, no hay rareza superior por la que fusionar — INFINITO
        // es secreto y no se puede conseguir por aquí. Avisamos y abortamos sin gastar nada.
        boolean allLegend = true;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < fusionSelection.size(); i++) {
            Card c = CardCollectionService.findCard(fusionSelection.get(i));
            if (c == null || c.getRarity() != CardCollectionService.Rarity.LEYENDA) {
                allLegend = false;
            }
            if (i > 0) names.append("  ·  ");
            names.append(c == null ? fusionSelection.get(i) : c.getName());
        }
        if (allLegend) {
            statusLabel.setText("Las leyendas son el tope. No se pueden fusionar para subir mas.");
            return;
        }
        org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder builder =
                new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        "Fusiona 3 cartas de la misma rareza para recibir 1 carta de la rareza superior. Las 3 cartas usadas se perderan.\n\n" + names,
                        "Confirmar fusión",
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
        builder.yesOrNo(this::performFuse, () -> {});
        Controllers.dialog(builder.build());
    }

    private void performFuse() {
        if (busy || userName == null || fusionSelection.size() != CardCollectionService.FUSION_COST) {
            return;
        }
        final List<String> picks = new ArrayList<>(fusionSelection);
        busy = true;
        statusLabel.setText("Fusionando…");
        new Thread(() -> {
            Card got = null;
            String error = null;
            try {
                got = CardCollectionService.fuse(userName, picks);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final Card finalGot = got;
            final String finalError = error;
            Platform.runLater(() -> {
                busy = false;
                if (finalError != null) {
                    statusLabel.setText("No se pudo guardar el inventario: " + finalError);
                } else if (finalGot == null) {
                    statusLabel.setText("No se pudo fusionar. Deben ser 3 cartas de la misma rareza y no pueden ser leyenda.");
                } else {
                    statusLabel.setText("¡Fusión completa! Has creado una carta "
                            + finalGot.getRarity().getDisplayName().toLowerCase() + ": " + finalGot.getName());
                    fusionSelection.clear();
                    CardRevealOverlay.play(finalGot, "Resultado de la fusion", this::refreshPage);
                }
                if (finalGot == null) {
                    refreshPage();
                }
            });
        }, "barrilmc-card-fuse").start();
    }

    private Region placeholder(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("server-progress-label");
        label.setWrapText(true);
        label.setMaxWidth(640);
        label.setPadding(new Insets(24, 12, 24, 12));
        return label;
    }
}
