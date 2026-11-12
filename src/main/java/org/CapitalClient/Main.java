
package org.CapitalClient;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import org.CapitalClient.model.MinecraftAccount;
import org.CapitalClient.model.ProfileSelection;
import org.CapitalClient.service.ProfileService;
import org.CapitalClient.service.InstalledProfileService;
import org.CapitalClient.service.InstalledProfileService.InstalledProfile;
import org.CapitalClient.service.MicrosoftAuthService;
import org.CapitalClient.ui.VersionManagerView;
import org.CapitalClient.ui.AccountsView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

public class Main extends Application {

    private Stage stage;
    private StackPane pageContainer;

    private final ProfileService profileService =
            new ProfileService();

    private final InstalledProfileService installedProfileService =
            new InstalledProfileService();

    private final MicrosoftAuthService microsoftAuthService =
            new MicrosoftAuthService();

    private ProfileSelection selectedProfile;

    private List<InstalledProfile> installedProfiles =
            new ArrayList<>();

    private InstalledProfile activeInstalledProfile;

    private MinecraftAccount minecraftAccount;
    private String localAccountName;
    private boolean localAccountActive;

    private boolean loginInProgress;

    private Label accountStatusLabel;
    private Button accountLoginButton;
    private Button accountLogoutButton;

    private double dragX;
    private double dragY;

    private double initialX;
    private double initialY;

    private double initialWidth;
    private double initialHeight;

    private double savedX;
    private double savedY;
    private double savedWidth;
    private double savedHeight;

    private boolean maximized;

    private boolean resizeLeft;
    private boolean resizeRight;
    private boolean resizeTop;
    private boolean resizeBottom;

    private static final double RESIZE_MARGIN = 7;

    @Override
    public void start(Stage stage) {

        this.stage = stage;

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("CapitalClient");

        stage.setMinWidth(950);
        stage.setMinHeight(620);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setStyle("-fx-background-color: #101116; -fx-font-family: 'Segoe UI';");

        HBox titleBar = createTitleBar();

        root.setTop(titleBar);
        root.setLeft(createSidebar());

        pageContainer = new StackPane();

        ScrollPane scrollPane = new ScrollPane(pageContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        scrollPane.setStyle(
                "-fx-background: #101116; -fx-background-color: #101116;"
                        + "-fx-control-inner-background: #101116; -fx-border-color: transparent;"
        );
        pageContainer.setStyle("-fx-background-color: #101116;");

        root.setCenter(scrollPane);

        loadSavedProfile();
        loadLocalAccount();
        refreshInstalledProfiles();
        showHome();

        Scene scene = new Scene(root, 1200, 760);

        var stylesheet = getClass().getResource(
                "/org/CapitalClient/ui/launcher.css"
        );

        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        setupWindowDragging(titleBar);
        setupWindowResizing(scene);

        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    // ---------------- TITLE BAR ----------------

    private HBox createTitleBar() {

        HBox bar = new HBox();

        bar.getStyleClass().add("title-bar");
        bar.setStyle("-fx-background-color: #171820; -fx-border-color: #292a35; -fx-border-width: 0 0 1 0;");
        bar.setMinHeight(58);
        bar.setPrefHeight(58);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 10, 0, 18));

        Label logo = new Label("capital");
        logo.setStyle("-fx-text-fill: #f3efff; -fx-font-size: 17px; -fx-font-weight: bold;");

        Label subtitle = new Label("CLIENT");
        subtitle.setStyle("-fx-text-fill: #a99bc8; -fx-font-size: 9px; -fx-font-weight: bold;");

        VBox branding = new VBox(0, logo, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        MenuButton accountMenu = createAccountMenu();

        Button minimize = createWindowButton("−", "Minimize");
        Button maximize = createWindowButton("□", "Maximize");
        Button close = createWindowButton("×", "Close");

        close.getStyleClass().add("close-button");

        minimize.setOnAction(e -> stage.setIconified(true));
        maximize.setOnAction(e -> toggleMaximize());
        close.setOnAction(e -> stage.close());

        bar.getChildren().addAll(
                branding,
                spacer,
                accountMenu,
                minimize,
                maximize,
                close
        );

        return bar;
    }

    private Button createWindowButton(
            String text,
            String tooltip
    ) {

        Button button = new Button(text);

        button.getStyleClass().add("window-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);

        return button;
    }

    private void setupWindowDragging(HBox bar) {

        bar.setOnMousePressed(event -> {

            if (event.getTarget() instanceof Button || maximized) {
                return;
            }

            dragX = event.getScreenX() - stage.getX();
            dragY = event.getScreenY() - stage.getY();
        });

        bar.setOnMouseDragged(event -> {

            if (event.getTarget() instanceof Button || maximized) {
                return;
            }

            stage.setX(event.getScreenX() - dragX);
            stage.setY(event.getScreenY() - dragY);
        });

        bar.setOnMouseClicked(event -> {

            if (event.getClickCount() == 2
                    && !(event.getTarget() instanceof Button)) {
                toggleMaximize();
            }
        });
    }

    private void toggleMaximize() {

        if (!maximized) {

            savedX = stage.getX();
            savedY = stage.getY();

            savedWidth = stage.getWidth();
            savedHeight = stage.getHeight();

            var bounds = Screen.getPrimary().getVisualBounds();

            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());

            maximized = true;

        } else {

            stage.setX(savedX);
            stage.setY(savedY);
            stage.setWidth(savedWidth);
            stage.setHeight(savedHeight);

            maximized = false;
        }
    }

    private void setupWindowResizing(Scene scene) {

        scene.setOnMouseMoved(event -> {

            if (maximized) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }

            updateResizeCursor(
                    scene,
                    event.getX(),
                    event.getY()
            );
        });

        scene.setOnMousePressed(event -> {

            if (maximized) return;

            double x = event.getX();
            double y = event.getY();

            resizeLeft = x <= RESIZE_MARGIN;
            resizeRight = x >= scene.getWidth() - RESIZE_MARGIN;
            resizeTop = y <= RESIZE_MARGIN;
            resizeBottom = y >= scene.getHeight() - RESIZE_MARGIN;

            initialX = stage.getX();
            initialY = stage.getY();

            initialWidth = stage.getWidth();
            initialHeight = stage.getHeight();

            dragX = event.getScreenX();
            dragY = event.getScreenY();
        });

        scene.setOnMouseDragged(event -> {

            if (maximized) return;

            if (!resizeLeft && !resizeRight
                    && !resizeTop && !resizeBottom) {
                return;
            }

            double dx = event.getScreenX() - dragX;
            double dy = event.getScreenY() - dragY;

            double width = initialWidth;
            double height = initialHeight;

            double x = initialX;
            double y = initialY;

            if (resizeRight) {
                width = Math.max(
                        stage.getMinWidth(),
                        initialWidth + dx
                );
            }

            if (resizeBottom) {
                height = Math.max(
                        stage.getMinHeight(),
                        initialHeight + dy
                );
            }

            if (resizeLeft) {

                width = initialWidth - dx;

                if (width >= stage.getMinWidth()) {
                    x = initialX + dx;
                } else {
                    width = stage.getMinWidth();
                    x = initialX + initialWidth - width;
                }
            }

            if (resizeTop) {

                height = initialHeight - dy;

                if (height >= stage.getMinHeight()) {
                    y = initialY + dy;
                } else {
                    height = stage.getMinHeight();
                    y = initialY + initialHeight - height;
                }
            }

            stage.setWidth(width);
            stage.setHeight(height);

            if (resizeLeft) stage.setX(x);
            if (resizeTop) stage.setY(y);
        });

        scene.setOnMouseReleased(event -> {

            resizeLeft = false;
            resizeRight = false;
            resizeTop = false;
            resizeBottom = false;

            scene.setCursor(Cursor.DEFAULT);
        });
    }

    private void updateResizeCursor(
            Scene scene,
            double x,
            double y
    ) {

        boolean left = x <= RESIZE_MARGIN;
        boolean right = x >= scene.getWidth() - RESIZE_MARGIN;

        boolean top = y <= RESIZE_MARGIN;
        boolean bottom = y >= scene.getHeight() - RESIZE_MARGIN;

        if ((left && top) || (right && bottom)) {
            scene.setCursor(Cursor.NW_RESIZE);
        } else if ((right && top) || (left && bottom)) {
            scene.setCursor(Cursor.NE_RESIZE);
        } else if (left || right) {
            scene.setCursor(Cursor.H_RESIZE);
        } else if (top || bottom) {
            scene.setCursor(Cursor.V_RESIZE);
        } else {
            scene.setCursor(Cursor.DEFAULT);
        }
    }

    // ---------------- SIDEBAR ----------------

    private VBox createSidebar() {

        VBox sidebar = new VBox(8);

        sidebar.getStyleClass().add("sidebar");
        sidebar.setStyle("-fx-background-color: #171820; -fx-border-color: #292a35; -fx-border-width: 0 1 0 0;");
        sidebar.setPadding(new Insets(25, 14, 20, 14));
        sidebar.setPrefWidth(220);
        sidebar.setMinWidth(200);

        Label library = new Label("LIBRARY");
        library.setStyle("-fx-text-fill: #8f8b9e; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 0 0 8 7;");

        Button home = createNavButton("⌂", "Home", true);
        Button versions = createNavButton("▣", "Versions", false);
        Button accounts = createNavButton("♙", "Accounts", false);
        Button mods = createNavButton("◇", "Mods", false);
        Button modpacks = createNavButton("▤", "Modpacks", false);

        Label preferences = new Label("PREFERENCES");
        preferences.setStyle("-fx-text-fill: #8f8b9e; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 14 0 8 7;");

        Button settings = createNavButton("⚙", "Settings", false);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label footer = new Label("CapitalClient  •  v1.0.0");
        footer.setStyle("-fx-text-fill: #777486; -fx-font-size: 11px; -fx-padding: 8 5 4 5;");

        home.setOnAction(e -> showHome());
        versions.setOnAction(e -> showVersions());
        accounts.setOnAction(e -> showAccounts());

        mods.setOnAction(e ->
                showMessage("Mod Manager will be added soon.")
        );

        modpacks.setOnAction(e ->
                showMessage("Modpack Manager will be added soon.")
        );

        settings.setOnAction(e ->
                showMessage("Settings will be added soon.")
        );

        sidebar.getChildren().addAll(
                library,
                home,
                versions,
                accounts,
                mods,
                modpacks,
                preferences,
                settings,
                spacer,
                footer
        );

        return sidebar;
    }

    private Button createNavButton(
            String icon,
            String name,
            boolean active
    ) {

        Button button = new Button(icon + "    " + name);

        button.getStyleClass().add("nav-button");
        button.setStyle(
                "-fx-background-color: " + (active ? "#29233a" : "transparent") + ";"
                        + "-fx-text-fill: " + (active ? "#c9b7ff" : "#c7c4d0") + ";"
                        + "-fx-font-size: 13px; -fx-background-radius: 9;"
                        + "-fx-padding: 0 14 0 14; -fx-cursor: hand;"
                        + "-fx-border-color: " + (active ? "#51416f" : "transparent") + ";"
                        + "-fx-border-radius: 9; -fx-border-width: 1;"
        );
        button.setOnMouseEntered(e -> {
            if (!button.getStyle().contains("#29233a")) {
                button.setStyle("-fx-background-color: #22212b; -fx-text-fill: #ffffff;"
                        + "-fx-font-size: 13px; -fx-background-radius: 9;"
                        + "-fx-padding: 0 14 0 14; -fx-cursor: hand;");
            }
        });
        button.setOnMouseExited(e -> button.setStyle(
                "-fx-background-color: " + (active ? "#29233a" : "transparent") + ";"
                        + "-fx-text-fill: " + (active ? "#c9b7ff" : "#c7c4d0") + ";"
                        + "-fx-font-size: 13px; -fx-background-radius: 9;"
                        + "-fx-padding: 0 14 0 14; -fx-cursor: hand;"
                        + "-fx-border-color: " + (active ? "#51416f" : "transparent") + ";"
                        + "-fx-border-radius: 9; -fx-border-width: 1;"
        ));

        if (active) {
            button.getStyleClass().add("nav-active");
        }

        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setPrefHeight(43);

        return button;
    }

    // ---------------- PROFILE STORAGE ----------------

    private void loadSavedProfile() {

        try {
            selectedProfile = profileService.load().orElse(null);
        } catch (IOException e) {
            selectedProfile = null;
            System.err.println("Profile loading failed: " + e.getMessage());
        }
    }

    private void createProfile(ProfileSelection profile) {

        try {
            profileService.save(profile);
            selectedProfile = profile;
            refreshInstalledProfiles();
            showHome();

            showMessage(
                    "Profile created successfully!\n\n"
                            + "Name: " + profile.name() + "\n"
                            + "Minecraft: " + profile.minecraftVersion() + "\n"
                            + "Loader: " + profile.loader() + "\n"
                            + "Loader Version: "
                            + (profile.loaderVersion().isBlank()
                            ? "Not required" : profile.loaderVersion())
            );

        } catch (IOException e) {
            showMessage("Unable to save profile: " + e.getMessage());
        }
    }

    // ---------------- INSTALLED PROFILES ----------------

    private void refreshInstalledProfiles() {

        try {
            installedProfiles =
                    installedProfileService.scanInstalledProfiles();

            Optional<String> savedId =
                    installedProfileService.loadSelectedProfileId();

            activeInstalledProfile = null;

            if (savedId.isPresent()) {
                for (InstalledProfile profile : installedProfiles) {
                    if (profile.id().equals(savedId.get())) {
                        activeInstalledProfile = profile;
                        break;
                    }
                }
            }

        } catch (IOException exception) {
            installedProfiles = new ArrayList<>();
            activeInstalledProfile = null;

            System.err.println(
                    "Installed profile scan failed: "
                            + exception.getMessage()
            );
        }
    }

    private void selectInstalledProfile(InstalledProfile profile) {

        try {
            installedProfileService.saveSelectedProfileId(profile.id());
            activeInstalledProfile = profile;
            showHome();

        } catch (IOException exception) {
            showMessage(
                    "Unable to save selected profile:\n"
                            + exception.getMessage()
            );
        }
    }

    private void renameInstalledProfile(InstalledProfile profile) {

        TextInputDialog dialog = new TextInputDialog(profile.name());

        dialog.setTitle("Rename Profile");
        dialog.setHeaderText("Edit profile display name");
        dialog.setContentText("New name:");

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        String newName = result.get().trim();

        if (newName.isBlank()) {
            showMessage("Profile name cannot be empty.");
            return;
        }

        try {
            installedProfileService.renameProfile(profile.id(), newName);
            refreshInstalledProfiles();
            showHome();

        } catch (IOException exception) {
            showMessage(
                    "Unable to rename profile:\n"
                            + exception.getMessage()
            );
        }
    }

    private void deleteInstalledProfile(InstalledProfile profile) {

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);

        confirmation.setTitle("Delete Profile");
        confirmation.setHeaderText("Delete " + profile.name() + "?");
        confirmation.setContentText(
                "This will delete this installed version folder:\n"
                        + profile.versionDirectory()
                        + "\n\nThis action cannot be undone."
        );

        Optional<ButtonType> result = confirmation.showAndWait();

        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        try {
            installedProfileService.deleteProfile(profile.id());
            refreshInstalledProfiles();
            showHome();
            showMessage("Profile deleted successfully.");

        } catch (IOException exception) {
            showMessage(
                    "Unable to delete profile:\n"
                            + exception.getMessage()
            );
        }
    }

    private VBox createInstalledProfilesSection() {

        VBox section = new VBox(14);
        section.setStyle("-fx-background-color: transparent;");

        Label heading = new Label("Installed Profiles");
        heading.setStyle("-fx-text-fill: #f2eff9; -fx-font-size: 18px; -fx-font-weight: bold;");

        Label subtitle = new Label(
                "Select a Minecraft installation to make it your active profile."
        );
        subtitle.setStyle("-fx-text-fill: #9694a5; -fx-font-size: 12px;");

        HBox titleRow = new HBox(12);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox titleInfo = new VBox(5, heading, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("Refresh");
        refreshButton.getStyleClass().add("secondary-button");

        refreshButton.setOnAction(event -> {
            refreshInstalledProfiles();
            showHome();
        });

        titleRow.getChildren().addAll(titleInfo, spacer, refreshButton);
        section.getChildren().add(titleRow);

        if (installedProfiles.isEmpty()) {

            VBox emptyCard = new VBox(10);
            emptyCard.getStyleClass().add("info-card");
            emptyCard.setPadding(new Insets(22));
            emptyCard.setStyle("-fx-background-color: #171820; -fx-background-radius: 13;"
                    + "-fx-border-color: #30313d; -fx-border-radius: 13;");

            Label emptyTitle = new Label("No installed versions found");
            emptyTitle.setStyle("-fx-text-fill: #f3f0fa; -fx-font-size: 15px; -fx-font-weight: bold;");

            Label emptyDescription = new Label(
                    "Install Minecraft or a mod loader from the Versions page. "
                            + "Installed versions will appear here."
            );
            emptyDescription.setStyle("-fx-text-fill: #9996a7; -fx-font-size: 12px;");
            emptyDescription.setWrapText(true);

            Button browseButton = new Button("Browse Versions");
            browseButton.setStyle("-fx-background-color: #8968e9; -fx-text-fill: white;"
                    + "-fx-background-radius: 8; -fx-padding: 10 16 10 16; -fx-font-weight: bold;");
            browseButton.setOnAction(event -> showVersions());

            emptyCard.getChildren().addAll(
                    emptyTitle, emptyDescription, browseButton
            );

            section.getChildren().add(emptyCard);
            return section;
        }

        for (InstalledProfile profile : installedProfiles) {
            section.getChildren().add(createInstalledProfileCard(profile));
        }

        return section;
    }

    private VBox createInstalledProfileCard(InstalledProfile profile) {

        VBox card = new VBox(12);
        card.getStyleClass().add("info-card");
        card.setPadding(new Insets(18));
        card.setStyle("-fx-background-color: #171820; -fx-background-radius: 13;"
                + "-fx-border-color: #30313d; -fx-border-radius: 13;");

        HBox topRow = new HBox(12);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox information = new VBox(6);

        Label name = new Label(profile.name());
        name.setStyle("-fx-text-fill: #f3f0fa; -fx-font-size: 15px; -fx-font-weight: bold;");

        Label id = new Label("ID: " + profile.id());
        id.setStyle("-fx-text-fill: #777486; -fx-font-size: 10px;");

        Label version = new Label(
                "Minecraft " + profile.minecraftVersion()
                        + "  •  " + profile.loader()
                        + (profile.loaderVersion().isBlank()
                        ? "" : " " + profile.loaderVersion())
        );
        version.setStyle("-fx-text-fill: #aaa6b8; -fx-font-size: 12px;");

        information.getChildren().addAll(name, id, version);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        boolean isSelected =
                activeInstalledProfile != null
                        && activeInstalledProfile.id().equals(profile.id());

        Label status = new Label(isSelected ? "SELECTED" : "INSTALLED");
        status.getStyleClass().add("game-tag");

        topRow.getChildren().addAll(information, spacer, status);

        HBox actions = new HBox(9);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button selectButton = new Button(isSelected ? "Selected" : "Select");
        selectButton.setStyle("-fx-background-color: #29233a; -fx-text-fill: #d7caff;"
                + "-fx-background-radius: 7; -fx-padding: 8 13 8 13;");
        selectButton.setDisable(isSelected);
        selectButton.setOnAction(event -> selectInstalledProfile(profile));

        Button editButton = new Button("Edit Name");
        editButton.setStyle("-fx-background-color: #24232e; -fx-text-fill: #d9d1ed;"
                + "-fx-border-color: #454052; -fx-border-radius: 7; -fx-background-radius: 7;"
                + "-fx-padding: 8 13 8 13;");
        editButton.setOnAction(event -> renameInstalledProfile(profile));

        Button deleteButton = new Button("Delete");
        deleteButton.setStyle("-fx-background-color: #382128; -fx-text-fill: #ffb5c0;"
                + "-fx-border-color: #70404b; -fx-border-radius: 7; -fx-background-radius: 7;"
                + "-fx-padding: 8 13 8 13;");
        deleteButton.setOnAction(event -> deleteInstalledProfile(profile));

        actions.getChildren().addAll(selectButton, editButton, deleteButton);
        card.getChildren().addAll(topRow, actions);

        return card;
    }

    // ---------------- TOP-RIGHT ACCOUNT MENU ----------------

    private MenuButton createAccountMenu() {

        String displayName = localAccountActive && localAccountName != null
                ? localAccountName
                : minecraftAccount != null
                ? minecraftAccount.username()
                : "Profile";

        MenuButton menu = new MenuButton("◉  " + displayName + "  ▾");
        menu.getStyleClass().add("account-menu-button");
        menu.setStyle(
                "-fx-background-color: #25232f;"
                        + "-fx-text-fill: white;"
                        + "-fx-background-radius: 9;"
                        + "-fx-padding: 10 16 10 16;"
                        + "-fx-font-size: 12px; -fx-font-weight: bold;"
        );

        MenuItem microsoftItem = new MenuItem(
                minecraftAccount == null
                        ? "Sign in with Microsoft"
                        : "Microsoft: " + minecraftAccount.username()
        );

        microsoftItem.setOnAction(event -> {
            if (minecraftAccount == null) {
                showAccounts();
            } else {
                localAccountActive = false;
                showHome();
            }
        });

        MenuItem localItem = new MenuItem(
                localAccountName == null
                        ? "Create Local Account..."
                        : "Use Local: " + localAccountName
        );

        localItem.setOnAction(event -> {
            if (localAccountName == null) {
                showAccounts();
            } else {
                localAccountActive = true;
                showHome();
            }
        });

        MenuItem accountsItem = new MenuItem("Manage Accounts");
        accountsItem.setOnAction(event -> showAccounts());

        MenuItem signOutItem = new MenuItem("Sign out");
        signOutItem.setOnAction(event -> {
            if (localAccountActive) {
                localAccountActive = false;
                showHome();
            } else {
                logoutMicrosoftAccount();
            }
        });

        menu.getItems().addAll(
                microsoftItem,
                localItem,
                new SeparatorMenuItem(),
                accountsItem,
                signOutItem
        );

        return menu;
    }

    // ---------------- LOCAL ACCOUNT STORAGE ----------------

    private void createLocalAccount() {

        TextInputDialog dialog = new TextInputDialog();

        dialog.setTitle("Create Local Account");
        dialog.setHeaderText("Create an offline launcher profile");
        dialog.setContentText("Username:");

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        String username = result.get().trim();

        if (!username.matches("[A-Za-z0-9_]{3,16}")) {
            showMessage(
                    "Username must be 3-16 characters and contain "
                            + "only letters, numbers or underscore."
            );
            return;
        }

        localAccountName = username;
        localAccountActive = true;

        try {
            saveLocalAccount();
        } catch (IOException exception) {
            showMessage(
                    "Could not save local account: "
                            + exception.getMessage()
            );
        }

        showHome();

        showMessage(
                "Local account created: " + username
                        + "\n\nThis is an offline profile, not a premium Minecraft account."
        );
    }

    private void loadLocalAccount() {

        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"))
                : Path.of(appData);
        Path file = base.resolve("CapitalClient").resolve("config").resolve("local-account.txt");

        try {
            if (Files.exists(file)) {
                String savedName = Files.readString(
                        file, StandardCharsets.UTF_8
                ).trim();

                if (!savedName.isBlank()) {
                    localAccountName = savedName;
                    localAccountActive = true;
                }
            }

        } catch (IOException exception) {
            System.err.println(
                    "Local account load failed: " + exception.getMessage()
            );
        }
    }

    private void saveLocalAccount() throws IOException {

        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"))
                : Path.of(appData);
        Path file = base.resolve("CapitalClient").resolve("config").resolve("local-account.txt");

        Files.createDirectories(file.getParent());

        Files.writeString(
                file, localAccountName, StandardCharsets.UTF_8
        );
    }

    private boolean hasActiveAccount() {
        return localAccountActive
                ? localAccountName != null
                : minecraftAccount != null;
    }

    private String activeAccountName() {
        return localAccountActive && localAccountName != null
                ? localAccountName
                : minecraftAccount != null
                ? minecraftAccount.username()
                : "Not signed in";
    }

    // ---------------- MICROSOFT LOGIN ----------------

    private void startMicrosoftLogin() {

        if (loginInProgress || minecraftAccount != null) {
            return;
        }

        loginInProgress = true;
        showAccounts();

        microsoftAuthService.authenticate().whenComplete((account, error) -> {

            Platform.runLater(() -> {

                loginInProgress = false;

                if (error != null) {

                    Throwable cause = error;

                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }

                    String message = cause.getMessage();

                    showAccounts();

                    showMessage(
                            "Microsoft login failed:\n\n"
                                    + (message == null
                                    ? "Unknown authentication error."
                                    : message)
                    );

                    return;
                }

                minecraftAccount = account;
                localAccountActive = false;

                showAccounts();

                showMessage(
                        "Microsoft login successful!\n\n"
                                + "Minecraft Username: "
                                + account.username()
                                + "\nUUID: "
                                + account.uuid()
                );
            });
        });
    }

    private void logoutMicrosoftAccount() {

        if (minecraftAccount == null) {
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);

        confirmation.setTitle("Sign Out");
        confirmation.setHeaderText("Sign out of Microsoft account?");
        confirmation.setContentText(
                "You will need to sign in again before playing."
        );

        Optional<ButtonType> result = confirmation.showAndWait();

        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        minecraftAccount = null;
        showHome();
    }

    // ---------------- ACCOUNTS PAGE ----------------

    private void showAccounts() {

        AccountsView view = new AccountsView(
                minecraftAccount,
                localAccountName,

                ignored -> startMicrosoftLogin(),

                username -> {

                    localAccountName = username;
                    localAccountActive = true;

                    try {
                        saveLocalAccount();
                        showAccounts();

                        showMessage(
                                "Local account saved: " + username
                                        + "\n\nThis account is for offline-mode servers only."
                        );

                    } catch (IOException exception) {

                        showMessage(
                                "Could not save local account: "
                                        + exception.getMessage()
                        );
                    }
                }
        );

        pageContainer.getChildren().setAll(view);
    }

    // ---------------- HOME PAGE ----------------

    private void showHome() {

        VBox content = new VBox(22);
        content.getStyleClass().add("content");
        content.setPadding(new Insets(30, 34, 34, 34));
        content.setStyle("-fx-background-color: #101116;");

        HBox pageHeader = new HBox();
        pageHeader.setAlignment(Pos.CENTER_LEFT);

        VBox headerText = new VBox(5);
        Label heading = new Label("Welcome to CapitalClient");
        heading.setStyle("-fx-text-fill: #f4f2fb; -fx-font-size: 25px; -fx-font-weight: bold;");
        Label subtitle = new Label("Your Minecraft experience starts here.");
        subtitle.setStyle("-fx-text-fill: #9694a5; -fx-font-size: 13px;");
        headerText.getChildren().addAll(heading, subtitle);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Label accountPill = new Label(
                (localAccountActive && localAccountName != null)
                        ? "LOCAL  •  " + localAccountName
                        : minecraftAccount != null
                        ? "MICROSOFT  •  " + minecraftAccount.username()
                        : "NOT SIGNED IN"
        );
        accountPill.setStyle(
                "-fx-background-color: #211c30; -fx-text-fill: #c9b8ff;"
                        + "-fx-background-radius: 18; -fx-padding: 9 15 9 15;"
                        + "-fx-font-size: 11px; -fx-font-weight: bold;"
        );
        pageHeader.getChildren().addAll(headerText, headerSpacer, accountPill);

        HBox hero = new HBox(28);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(30));
        hero.setMinHeight(300);
        hero.setMaxWidth(Double.MAX_VALUE);
        hero.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #211d31, #171820 68%, #191624);"
                        + "-fx-background-radius: 20; -fx-border-color: #353044;"
                        + "-fx-border-radius: 20; -fx-border-width: 1;"
        );

        VBox gameInfo = new VBox(13);
        gameInfo.setAlignment(Pos.CENTER_LEFT);

        Label tag = new Label("MINECRAFT JAVA EDITION");
        tag.setStyle("-fx-text-fill: #b9a4ff; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label gameTitle = new Label("Ready for your next adventure?");
        gameTitle.setWrapText(true);
        gameTitle.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 27px; -fx-font-weight: bold;");

        Label activeName = new Label(
                activeInstalledProfile != null
                        ? activeInstalledProfile.name()
                        : "No installed profile selected"
        );
        activeName.setStyle("-fx-text-fill: #e8e3f5; -fx-font-size: 15px; -fx-font-weight: bold;");

        String versionText = activeInstalledProfile == null
                ? "Install or select a Minecraft version to continue."
                : "Minecraft " + activeInstalledProfile.minecraftVersion()
                + "  •  " + activeInstalledProfile.loader()
                + (activeInstalledProfile.loaderVersion() == null
                || activeInstalledProfile.loaderVersion().isBlank()
                ? "" : " " + activeInstalledProfile.loaderVersion());

        Label versionInfo = new Label(versionText);
        versionInfo.setWrapText(true);
        versionInfo.setStyle("-fx-text-fill: #a5a1b2; -fx-font-size: 13px;");

        Button play = new Button("▶     PLAY MINECRAFT");
        play.setPrefHeight(52);
        play.setPrefWidth(260);
        play.setMaxWidth(260);
        play.setStyle(
                "-fx-background-color: #8968e9; -fx-text-fill: white;"
                        + "-fx-font-size: 14px; -fx-font-weight: bold;"
                        + "-fx-background-radius: 10; -fx-cursor: hand;"
                        + "-fx-padding: 13 22 13 22;"
        );
        play.setDisable(activeInstalledProfile == null || loginInProgress);
        play.setOnMouseEntered(e -> {
            if (!play.isDisabled()) play.setStyle(
                    "-fx-background-color: #9a7af5; -fx-text-fill: white;"
                            + "-fx-font-size: 14px; -fx-font-weight: bold;"
                            + "-fx-background-radius: 10; -fx-cursor: hand;"
                            + "-fx-padding: 13 22 13 22;"
            );
        });
        play.setOnMouseExited(e -> play.setStyle(
                "-fx-background-color: #8968e9; -fx-text-fill: white;"
                        + "-fx-font-size: 14px; -fx-font-weight: bold;"
                        + "-fx-background-radius: 10; -fx-cursor: hand;"
                        + "-fx-padding: 13 22 13 22;"
        ));

        play.setOnAction(event -> {
            if (activeInstalledProfile == null) {
                showMessage("Please install and select a Minecraft profile first.");
                return;
            }
            if (localAccountActive && localAccountName != null) {
                showMessage("Selected local account: " + localAccountName
                        + "\n\nOffline account support is not yet connected to the game launch service.");
            } else if (minecraftAccount == null) {
                showMessage("Please connect a Microsoft account from Accounts first.");
            } else {
                showMessage("Microsoft account connected: " + minecraftAccount.username()
                        + "\nSelected profile: " + activeInstalledProfile.name()
                        + "\n\nThe game launch service still needs to be connected to this Home button.");
            }
        });

        ComboBox<InstalledProfile> versionSelector = new ComboBox<>();
        versionSelector.getItems().setAll(installedProfiles);
        versionSelector.setPrefWidth(260);
        versionSelector.setMaxWidth(260);
        versionSelector.setPrefHeight(42);
        versionSelector.setPromptText("Select installed version");
        versionSelector.setStyle(
                "-fx-background-color: #24232e; -fx-border-color: #454052;"
                        + "-fx-border-radius: 8; -fx-background-radius: 8;"
                        + "-fx-mark-color: #c7b7ff; -fx-text-fill: #f4f2fb;"
        );
        versionSelector.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(InstalledProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.name() + "  •  " + item.minecraftVersion() + "  •  " + item.loader());
                setStyle("-fx-background-color: #24232e; -fx-text-fill: #f4f2fb;");
            }
        });
        versionSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(InstalledProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null
                        ? "Select installed version"
                        : item.name() + "  •  " + item.minecraftVersion());
                setStyle("-fx-background-color: transparent; -fx-text-fill: #f4f2fb;");
            }
        });
        if (activeInstalledProfile != null) versionSelector.setValue(activeInstalledProfile);
        versionSelector.setOnAction(event -> {
            InstalledProfile chosen = versionSelector.getValue();
            if (chosen != null && (activeInstalledProfile == null
                    || !activeInstalledProfile.id().equals(chosen.id()))) {
                selectInstalledProfile(chosen);
            }
        });

        Button manage = new Button("Manage Versions  →");
        manage.setPrefHeight(42);
        manage.setStyle(
                "-fx-background-color: #292735; -fx-text-fill: #e7e1f5;"
                        + "-fx-font-size: 13px; -fx-background-radius: 8;"
                        + "-fx-border-color: #454052; -fx-border-radius: 8;"
                        + "-fx-padding: 10 18 10 18; -fx-cursor: hand;"
        );
        manage.setOnAction(event -> showVersions());

        gameInfo.getChildren().addAll(tag, gameTitle, activeName, versionInfo, play, versionSelector, manage);
        HBox.setHgrow(gameInfo, Priority.ALWAYS);

        VBox artwork = new VBox(12);
        artwork.setAlignment(Pos.CENTER);
        artwork.setMinWidth(230);
        artwork.setPrefWidth(280);
        artwork.setMaxWidth(320);
        artwork.setMinHeight(220);
        artwork.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #302746, #1b1927);"
                        + "-fx-background-radius: 18; -fx-border-color: #4a3b68;"
                        + "-fx-border-radius: 18;"
        );

        Label artIcon = new Label("✦");
        artIcon.setStyle("-fx-text-fill: #bca8ff; -fx-font-size: 76px;");
        Label artTitle = new Label("YOUR WORLD");
        artTitle.setStyle("-fx-text-fill: #f4f0ff; -fx-font-size: 15px; -fx-font-weight: bold;");
        Label artSubtitle = new Label("A new adventure awaits");
        artSubtitle.setStyle("-fx-text-fill: #aaa2bd; -fx-font-size: 12px;");
        artwork.getChildren().addAll(artIcon, artTitle, artSubtitle);
        hero.getChildren().addAll(gameInfo, artwork);

        HBox statusCards = new HBox(14);
        statusCards.setAlignment(Pos.CENTER);
        statusCards.getChildren().addAll(
                createDashboardCard("ACCOUNT", activeAccountName(),
                        localAccountActive ? "Offline profile" : minecraftAccount == null ? "Connect an account to play" : "Microsoft connected"),
                createDashboardCard("INSTALLATION",
                        activeInstalledProfile == null ? "Not selected" : activeInstalledProfile.minecraftVersion(),
                        activeInstalledProfile == null ? "No game version" : activeInstalledProfile.loader()),
                createDashboardCard("LIBRARY", String.valueOf(installedProfiles.size()),
                        "Installed profiles")
        );
        for (var node : statusCards.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);

        HBox profilesHeader = new HBox();
        profilesHeader.setAlignment(Pos.CENTER_LEFT);
        VBox profileHeadingBox = new VBox(4);
        Label profilesHeading = new Label("Installed Profiles");
        profilesHeading.setStyle("-fx-text-fill: #f2eff9; -fx-font-size: 19px; -fx-font-weight: bold;");
        Label profilesSubtitle = new Label("Choose an installation to make it your active profile.");
        profilesSubtitle.setStyle("-fx-text-fill: #9694a5; -fx-font-size: 12px;");
        profileHeadingBox.getChildren().addAll(profilesHeading, profilesSubtitle);
        Region profileSpacer = new Region();
        HBox.setHgrow(profileSpacer, Priority.ALWAYS);
        Button refresh = new Button("↻  Refresh");
        refresh.setStyle(
                "-fx-background-color: #24232e; -fx-text-fill: #d9d1ed;"
                        + "-fx-border-color: #454052; -fx-border-radius: 8;"
                        + "-fx-background-radius: 8; -fx-padding: 9 15 9 15;"
                        + "-fx-cursor: hand;"
        );
        refresh.setOnAction(e -> {
            refreshInstalledProfiles();
            showHome();
        });
        profilesHeader.getChildren().addAll(profileHeadingBox, profileSpacer, refresh);

        content.getChildren().addAll(pageHeader, hero, statusCards, profilesHeader,
                createInstalledProfilesSection());
        pageContainer.getChildren().setAll(content);
    }

    private VBox createDashboardCard(String title, String value, String subtitle) {
        VBox card = new VBox(7);
        card.setPadding(new Insets(17));
        card.setMinHeight(92);
        card.setStyle(
                "-fx-background-color: #171820; -fx-background-radius: 13;"
                        + "-fx-border-color: #30313d; -fx-border-radius: 13;"
        );
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: #9e91bd; -fx-font-size: 10px; -fx-font-weight: bold;");
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-text-fill: #f3f0fa; -fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setStyle("-fx-text-fill: #9996a7; -fx-font-size: 11px;");
        valueLabel.setWrapText(true);
        subtitleLabel.setWrapText(true);
        card.getChildren().addAll(titleLabel, valueLabel, subtitleLabel);
        return card;
    }

    // ---------------- VERSION MANAGER ----------------

    private void showVersions() {

        VersionManagerView view =
                new VersionManagerView(this::createProfile);

        pageContainer.getChildren().setAll(view);
    }

    // ---------------- INFORMATION CARD ----------------

    private VBox createInfoCard(
            String title,
            String value,
            String description
    ) {

        VBox card = new VBox(9);

        card.getStyleClass().add("info-card");
        card.setPadding(new Insets(19));

        Label heading = new Label(title);
        heading.getStyleClass().add("card-heading");

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("card-value");

        Label subtitle = new Label(description);
        subtitle.getStyleClass().add("card-description");

        card.getChildren().addAll(
                heading,
                valueLabel,
                subtitle
        );

        return card;
    }

    // ---------------- MESSAGE ----------------

    private void showMessage(String message) {

        Alert alert = new Alert(Alert.AlertType.INFORMATION);

        alert.setTitle("CapitalClient");
        alert.setHeaderText(null);
        alert.setContentText(message);

        alert.show();
    }

    // ---------------- SHUTDOWN ----------------

    @Override
    public void stop() {
        microsoftAuthService.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
