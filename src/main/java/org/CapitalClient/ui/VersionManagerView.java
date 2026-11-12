
package org.CapitalClient.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import org.CapitalClient.model.MinecraftVersion;
import org.CapitalClient.model.ProfileSelection;
import org.CapitalClient.service.LoaderVersionService;
import org.CapitalClient.service.VersionManifestService;

import java.util.List;
import java.util.function.Consumer;

public class VersionManagerView extends VBox {

    private final VersionManifestService manifestService =
            new VersionManifestService();

    private final LoaderVersionService loaderService =
            new LoaderVersionService();

    private final ListView<MinecraftVersion> versionList =
            new ListView<>();

    private final TextField searchField = new TextField();
    private final TextField profileNameField = new TextField();

    private final ComboBox<String> filterBox = new ComboBox<>();
    private final ComboBox<String> loaderBox = new ComboBox<>();
    private final ComboBox<String> loaderVersionBox = new ComboBox<>();

    private final Label statusLabel =
            new Label("Loading Minecraft versions...");

    private final Label selectedName =
            new Label("No version selected");

    private final Label selectedType =
            new Label("Select a Minecraft version");

    private final Label loaderStatus =
            new Label("Select a Minecraft version first");

    private final Button createButton =
            new Button("Create Profile");

    private final Consumer<ProfileSelection> onProfileCreated;

    private List<MinecraftVersion> allVersions = List.of();

    private FilteredList<MinecraftVersion> filteredVersions;

    private MinecraftVersion selectedMinecraftVersion;

    private long loaderRequestId = 0;

    public VersionManagerView(
            Consumer<ProfileSelection> onProfileCreated
    ) {

        this.onProfileCreated = onProfileCreated;

        setSpacing(18);
        setPadding(new Insets(30));
        setStyle("-fx-background-color: #111217;");

        loaderBox.getItems().addAll(
                "Vanilla",
                "Fabric",
                "Forge",
                "NeoForge",
                "Quilt",
                "OptiFine"
        );

        loaderBox.setValue("Vanilla");

        loaderVersionBox.setPromptText("Select loader version");

        profileNameField.setPromptText("My Minecraft Profile");

        getChildren().addAll(
                createHeader(),
                createToolbar(),
                createVersionArea(),
                createProfileControls(),
                statusLabel
        );

        setupVersionList();

        loaderBox.valueProperty().addListener(
                (obs, oldValue, newValue) -> loadLoaderVersions()
        );

        loaderVersionBox.valueProperty().addListener(
                (obs, oldValue, newValue) -> updateCreateButton()
        );

        createButton.setDisable(true);

        createButton.setOnAction(e -> createProfile());

        loadMinecraftVersions();
    }

    private VBox createHeader() {

        Label title = new Label("Game Profiles");
        title.setStyle(
                "-fx-font-size: 27px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: #f4f4f7;"
        );

        Label subtitle = new Label(
                "Choose Minecraft versions, mod loaders and configurations."
        );

        subtitle.setStyle("-fx-text-fill: #9294a2;");

        return new VBox(7, title, subtitle);
    }

    private HBox createToolbar() {

        searchField.setPromptText("Search Minecraft versions...");
        searchField.setPrefHeight(40);
        searchField.setStyle(inputStyle());

        HBox.setHgrow(searchField, Priority.ALWAYS);

        filterBox.getItems().addAll(
                "All",
                "Release",
                "Snapshot",
                "Old Beta",
                "Old Alpha"
        );

        filterBox.setValue("All");
        filterBox.setPrefWidth(140);

        Button refresh = new Button("↻ Refresh");
        refresh.setPrefHeight(40);
        refresh.setStyle(buttonStyle());

        refresh.setOnAction(e -> loadMinecraftVersions());

        searchField.textProperty().addListener(
                (obs, oldValue, newValue) -> applyFilter()
        );

        filterBox.valueProperty().addListener(
                (obs, oldValue, newValue) -> applyFilter()
        );

        return new HBox(10, searchField, filterBox, refresh);
    }

    private HBox createVersionArea() {

        versionList.setPrefHeight(390);
        versionList.setStyle(
                "-fx-background-color: #191a23;" +
                        "-fx-control-inner-background: #191a23;" +
                        "-fx-background-radius: 8;" +
                        "-fx-border-color: #292a36;" +
                        "-fx-border-radius: 8;"
        );

        VBox details = new VBox(14);
        details.setPadding(new Insets(20));
        details.setPrefWidth(260);
        details.setMinWidth(220);

        details.setStyle(
                "-fx-background-color: #1b1c25;" +
                        "-fx-background-radius: 9;" +
                        "-fx-border-color: #292a36;" +
                        "-fx-border-radius: 9;"
        );

        Label heading = new Label("MINECRAFT VERSION");
        heading.setStyle(
                "-fx-text-fill: #858796;" +
                        "-fx-font-size: 10px;" +
                        "-fx-font-weight: bold;"
        );

        selectedName.setStyle(
                "-fx-text-fill: white;" +
                        "-fx-font-size: 23px;" +
                        "-fx-font-weight: bold;"
        );

        selectedType.setStyle(
                "-fx-text-fill: #a994f5;" +
                        "-fx-font-size: 12px;"
        );

        Label loaderHeading = new Label("MOD LOADER");
        loaderHeading.setStyle(labelStyle());

        Label loaderVersionHeading = new Label("LOADER VERSION");
        loaderVersionHeading.setStyle(labelStyle());

        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.setPrefHeight(38);
        loaderBox.setStyle(inputStyle());

        loaderVersionBox.setMaxWidth(Double.MAX_VALUE);
        loaderVersionBox.setPrefHeight(38);
        loaderVersionBox.setStyle(inputStyle());

        loaderStatus.setWrapText(true);
        loaderStatus.setStyle(
                "-fx-text-fill: #9294a2;" +
                        "-fx-font-size: 10px;"
        );

        details.getChildren().addAll(
                heading,
                new Separator(),
                selectedName,
                selectedType,
                loaderHeading,
                loaderBox,
                loaderVersionHeading,
                loaderVersionBox,
                loaderStatus
        );

        HBox.setHgrow(versionList, Priority.ALWAYS);

        return new HBox(14, versionList, details);
    }

    private VBox createProfileControls() {

        Label heading = new Label("PROFILE NAME");
        heading.setStyle(labelStyle());

        profileNameField.setPrefHeight(40);
        profileNameField.setStyle(inputStyle());

        createButton.setPrefSize(190, 44);
        createButton.setStyle(
                "-fx-background-color: #8b6bea;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 7;" +
                        "-fx-cursor: hand;"
        );

        HBox row = new HBox(12, profileNameField, createButton);
        row.setAlignment(Pos.CENTER_LEFT);

        HBox.setHgrow(profileNameField, Priority.ALWAYS);

        return new VBox(9, heading, row);
    }

    private void setupVersionList() {

        versionList.setCellFactory(list -> new ListCell<>() {

            @Override
            protected void updateItem(
                    MinecraftVersion version,
                    boolean empty
            ) {

                super.updateItem(version, empty);

                if (empty || version == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }

                Label name = new Label(version.id());
                name.setStyle(
                        "-fx-text-fill: #f4f4f7;" +
                                "-fx-font-size: 14px;" +
                                "-fx-font-weight: bold;"
                );

                Label type = new Label(version.getDisplayType());
                type.setStyle(
                        "-fx-text-fill: #999bad;" +
                                "-fx-font-size: 10px;"
                );

                VBox info = new VBox(4, name, type);

                HBox row = new HBox(info);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 12, 8, 12));

                if (version.isLatest()) {

                    Label latest = new Label("LATEST");

                    latest.setStyle(
                            "-fx-text-fill: #bca7ff;" +
                                    "-fx-font-size: 9px;" +
                                    "-fx-font-weight: bold;"
                    );

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    row.getChildren().addAll(spacer, latest);
                }

                setGraphic(row);
            }
        });

        versionList.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, oldValue, version) -> {

                    selectedMinecraftVersion = version;

                    if (version == null) {
                        selectedName.setText("No version selected");
                        selectedType.setText("Select a version");
                        loaderRequestId++;
                        updateCreateButton();
                        return;
                    }

                    selectedName.setText(version.id());
                    selectedType.setText(version.getDisplayType());

                    loadLoaderVersions();
                });
    }

    private void loadMinecraftVersions() {

        statusLabel.setText("Fetching official Minecraft versions...");
        versionList.setDisable(true);

        manifestService.loadVersions().whenComplete((versions, error) -> {

            Platform.runLater(() -> {

                versionList.setDisable(false);

                if (error != null) {
                    statusLabel.setText(
                            "Unable to load Minecraft versions: "
                                    + error.getMessage()
                    );
                    return;
                }

                allVersions = versions;

                filteredVersions = new FilteredList<>(
                        FXCollections.observableArrayList(allVersions),
                        version -> true
                );

                versionList.setItems(filteredVersions);

                applyFilter();

                statusLabel.setText(
                        "Loaded " + allVersions.size()
                                + " official Minecraft versions."
                );
            });
        });
    }

    private void applyFilter() {

        if (filteredVersions == null) {
            return;
        }

        String search = searchField.getText()
                .trim()
                .toLowerCase();

        String type = filterBox.getValue();

        filteredVersions.setPredicate(version -> {

            boolean matchesSearch = version.id()
                    .toLowerCase()
                    .contains(search);

            boolean matchesType = "All".equals(type)
                    || version.getDisplayType().equals(type);

            return matchesSearch && matchesType;
        });
    }

    private void loadLoaderVersions() {

        long requestId = ++loaderRequestId;

        if (selectedMinecraftVersion == null) {
            updateCreateButton();
            return;
        }

        String minecraft = selectedMinecraftVersion.id();
        String loader = loaderBox.getValue();

        loaderVersionBox.getItems().clear();
        loaderVersionBox.setValue(null);
        loaderVersionBox.setDisable(true);

        createButton.setDisable(true);

        if ("Vanilla".equals(loader)) {

            loaderVersionBox.getItems().setAll("Not required");
            loaderVersionBox.setValue("Not required");

            loaderStatus.setText("Standard Minecraft installation.");

            updateCreateButton();
            return;
        }

        if ("OptiFine".equals(loader)) {

            loaderVersionBox.getItems().setAll(
                    "Manual installation required"
            );

            loaderVersionBox.setValue(
                    "Manual installation required"
            );

            loaderVersionBox.setDisable(false);

            loaderStatus.setText(
                    "OptiFine must be downloaded from its official website. "
                            + "Automatic installation is not available yet."
            );

            updateCreateButton();
            return;
        }

        loaderStatus.setText(
                "Fetching compatible " + loader + " versions..."
        );

        loaderService.loadVersions(minecraft, loader)
                .whenComplete((versions, error) -> {

                    Platform.runLater(() -> {

                        if (requestId != loaderRequestId
                                || selectedMinecraftVersion == null
                                || !minecraft.equals(
                                selectedMinecraftVersion.id()
                        )
                                || !loader.equals(loaderBox.getValue())) {
                            return;
                        }

                        if (error != null) {

                            loaderStatus.setText(
                                    "Unable to fetch versions: "
                                            + error.getMessage()
                            );

                            updateCreateButton();
                            return;
                        }

                        if (versions.isEmpty()) {

                            loaderStatus.setText(
                                    "No compatible versions found for "
                                            + minecraft + "."
                            );

                            updateCreateButton();
                            return;
                        }

                        loaderVersionBox.setDisable(false);
                        loaderVersionBox.getItems().setAll(versions);

                        loaderVersionBox.getSelectionModel().selectFirst();

                        loaderStatus.setText(
                                "Found " + versions.size()
                                        + " compatible versions."
                        );

                        updateCreateButton();
                    });
                });
    }

    private void updateCreateButton() {

        if (selectedMinecraftVersion == null) {
            createButton.setDisable(true);
            return;
        }

        String loader = loaderBox.getValue();
        String loaderVersion = loaderVersionBox.getValue();

        boolean valid;

        if ("Vanilla".equals(loader)) {
            valid = true;
        } else {
            valid = loaderVersion != null
                    && !loaderVersion.isBlank()
                    && (!"OptiFine".equals(loader)
                    || "Manual installation required".equals(loaderVersion));
        }

        createButton.setDisable(!valid);
    }

    private void createProfile() {

        if (selectedMinecraftVersion == null) {
            return;
        }

        String loader = loaderBox.getValue();

        String name = profileNameField.getText().trim();

        if (name.isEmpty()) {
            name = selectedMinecraftVersion.id() + "-" + loader;
        }

        String loaderVersion = "Vanilla".equals(loader)
                ? ""
                : loaderVersionBox.getValue();

        ProfileSelection profile = new ProfileSelection(
                name,
                selectedMinecraftVersion.id(),
                loader,
                loaderVersion
        );

        onProfileCreated.accept(profile);
    }

    private String inputStyle() {

        return "-fx-background-color: #20212b;"
                + "-fx-text-fill: white;"
                + "-fx-prompt-text-fill: #777987;"
                + "-fx-background-radius: 7;"
                + "-fx-border-color: #343541;"
                + "-fx-border-radius: 7;";
    }

    private String labelStyle() {

        return "-fx-text-fill: #858796;"
                + "-fx-font-size: 10px;"
                + "-fx-font-weight: bold;";
    }

    private String buttonStyle() {

        return "-fx-background-color: #292a38;"
                + "-fx-text-fill: white;"
                + "-fx-background-radius: 7;"
                + "-fx-cursor: hand;";
    }
}
