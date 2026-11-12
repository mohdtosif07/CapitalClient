
package org.CapitalClient.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import org.CapitalClient.model.ProfileSelection;
import org.CapitalClient.service.GameDirectoryService;
import org.CapitalClient.service.MinecraftInstallerService;
import org.CapitalClient.service.ModLoaderInstallerService;
import org.CapitalClient.service.RuntimeManagerService;

import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public class InstallationView extends VBox {

    private final ProfileSelection profile;

    private final Consumer<MinecraftInstallerService.InstallResult> onInstalled;

    private final MinecraftInstallerService installer;
    private final ModLoaderInstallerService loaderInstaller;
    private final RuntimeManagerService runtimeManager;

    private final Label statusLabel =
            new Label("Ready to install");

    private final Label progressLabel =
            new Label("0%");

    private final ProgressBar progressBar =
            new ProgressBar(0);

    private final Button installButton =
            new Button("Install Minecraft");

    public InstallationView(
            ProfileSelection profile,
            Consumer<MinecraftInstallerService.InstallResult> onInstalled
    ) {

        this.profile = profile;
        this.onInstalled = onInstalled;

        GameDirectoryService directories =
                new GameDirectoryService();

        this.installer =
                new MinecraftInstallerService(directories);

        this.loaderInstaller =
                new ModLoaderInstallerService(directories);

        this.runtimeManager =
                new RuntimeManagerService(directories);

        buildUI();
    }

    private void buildUI() {

        setSpacing(18);
        setPadding(new Insets(35));
        setAlignment(Pos.TOP_LEFT);
        setFillWidth(true);

        setStyle("-fx-background-color: #111318;");

        Label heading = new Label("Install Minecraft");

        heading.setFont(
                Font.font("Arial", FontWeight.BOLD, 28)
        );
        heading.setTextFill(Color.WHITE);

        Label versionLabel = new Label(
                profile.name()
                        + "  •  Minecraft "
                        + profile.minecraftVersion()
        );

        versionLabel.setFont(Font.font("Arial", 15));
        versionLabel.setTextFill(Color.web("#A7A9B3"));

        String loaderVersion = profile.loaderVersion();

        Label loaderLabel = new Label(
                "Loader: " + profile.loader()
                        + (loaderVersion == null
                        || loaderVersion.isBlank()
                        ? ""
                        : " " + loaderVersion)
        );

        loaderLabel.setTextFill(Color.web("#A7A9B3"));
        loaderLabel.setFont(Font.font("Arial", 13));

        Label infoLabel = new Label(
                "Minecraft, selected loader and compatible Java runtime "
                        + "will be installed automatically."
        );

        infoLabel.setWrapText(true);
        infoLabel.setTextFill(Color.web("#858995"));
        infoLabel.setFont(Font.font("Arial", 13));

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(12);
        progressBar.setProgress(0);

        progressLabel.setTextFill(Color.WHITE);
        progressLabel.setFont(
                Font.font("Arial", FontWeight.BOLD, 13)
        );

        statusLabel.setTextFill(Color.web("#B8BBC5"));
        statusLabel.setWrapText(true);

        installButton.setPrefWidth(220);
        installButton.setPrefHeight(42);

        installButton.setStyle(
                "-fx-background-color: #8B5CF6;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 14px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 8px;" +
                        "-fx-cursor: hand;"
        );

        installButton.setOnAction(event -> startInstallation());

        getChildren().addAll(
                heading,
                versionLabel,
                loaderLabel,
                infoLabel,
                progressBar,
                progressLabel,
                statusLabel,
                installButton
        );
    }

    private void startInstallation() {

        installButton.setDisable(true);
        installButton.setText("Installing...");

        progressBar.setProgress(
                ProgressBar.INDETERMINATE_PROGRESS
        );

        progressLabel.setText("Please wait");

        statusLabel.setText(
                "Starting Minecraft installation..."
        );

        statusLabel.setTextFill(Color.web("#B8BBC5"));

        installer.install(
                profile.minecraftVersion(),
                (completed, total, message) -> Platform.runLater(() -> {

                    statusLabel.setText(message);

                    if (total > 0) {

                        double progress =
                                (double) completed / total;

                        progressBar.setProgress(progress);

                        progressLabel.setText(
                                Math.round(progress * 100) + "%"
                        );

                    } else {

                        progressBar.setProgress(
                                ProgressBar.INDETERMINATE_PROGRESS
                        );
                    }
                })

        ).thenCompose(result -> {

            Platform.runLater(() -> {

                statusLabel.setText(
                        "Minecraft installed. Installing selected loader..."
                );

                progressBar.setProgress(
                        ProgressBar.INDETERMINATE_PROGRESS
                );

                progressLabel.setText("Mod loader");
            });

            return loaderInstaller.install(
                    profile,
                    message -> Platform.runLater(() -> {

                        statusLabel.setText(message);

                        progressBar.setProgress(
                                ProgressBar.INDETERMINATE_PROGRESS
                        );
                    })

            ).thenApply(ignored -> result);

        }).thenCompose(result -> {

            Platform.runLater(() -> {

                statusLabel.setText(
                        "Loader installed. Preparing compatible Java runtime..."
                );

                progressBar.setProgress(
                        ProgressBar.INDETERMINATE_PROGRESS
                );

                progressLabel.setText("Java runtime");
            });

            return runtimeManager.installForVersion(
                    profile.minecraftVersion(),
                    (completed, total, message) ->
                            Platform.runLater(() -> {

                                statusLabel.setText(message);

                                if (total > 0) {

                                    double progress =
                                            (double) completed / total;

                                    progressBar.setProgress(progress);

                                    progressLabel.setText(
                                            Math.round(progress * 100) + "%"
                                    );

                                } else {

                                    progressBar.setProgress(
                                            ProgressBar.INDETERMINATE_PROGRESS
                                    );
                                }
                            })

            ).thenApply(runtimeResult -> result);

        }).whenComplete((result, error) -> Platform.runLater(() -> {

            if (error != null) {

                Throwable cause = error;

                while ((cause instanceof CompletionException
                        || cause instanceof RuntimeException)
                        && cause.getCause() != null) {

                    cause = cause.getCause();
                }

                statusLabel.setText(
                        "Installation failed: " + cause.getMessage()
                );

                statusLabel.setTextFill(
                        Color.web("#FF6B6B")
                );

                installButton.setDisable(false);
                installButton.setText("Retry Installation");

                progressBar.setProgress(0);
                progressLabel.setText("Failed");

            } else {

                progressBar.setProgress(1);
                progressLabel.setText("100%");

                statusLabel.setText(
                        "Minecraft, loader and Java runtime "
                                + "installed successfully!"
                );

                statusLabel.setTextFill(
                        Color.web("#4ADE80")
                );

                installButton.setText("Installed");
                installButton.setDisable(true);

                if (onInstalled != null) {
                    onInstalled.accept(result);
                }
            }
        }));
    }

    public void shutdown() {

        installer.shutdown();
        loaderInstaller.shutdown();
        runtimeManager.shutdown();
    }
}
