
package org.CapitalClient.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.CapitalClient.model.MinecraftAccount;

import java.util.function.Consumer;

public class AccountsView extends BorderPane {

    private final Consumer<Void> microsoftLogin;
    private final Consumer<String> localAccount;
    private final MinecraftAccount minecraftAccount;
    private final String localAccountName;

    private boolean microsoftSelected = true;

    private VBox rightPanel;
    private Button microsoftCard;
    private Button localCard;

    public AccountsView(
            MinecraftAccount minecraftAccount,
            String localAccountName,
            Consumer<Void> microsoftLogin,
            Consumer<String> localAccount
    ) {
        this.minecraftAccount = minecraftAccount;
        this.localAccountName = localAccountName;
        this.microsoftLogin = microsoftLogin;
        this.localAccount = localAccount;

        getStyleClass().add("accounts-page");
        setPadding(new Insets(28, 30, 28, 30));

        createPage();
    }

    private void createPage() {
        VBox page = new VBox(25);

        Label heading = new Label("Accounts");
        heading.getStyleClass().add("heading");

        Label subtitle = new Label(
                "Manage your Minecraft accounts."
        );
        subtitle.getStyleClass().add("description");

        VBox headingBox = new VBox(6, heading, subtitle);

        HBox content = new HBox(30);
        content.setAlignment(Pos.TOP_LEFT);

        VBox accountCards = createAccountCards();

        rightPanel = new VBox();
        rightPanel.setMinWidth(350);
        rightPanel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);

        content.getChildren().addAll(accountCards, rightPanel);

        page.getChildren().addAll(headingBox, content);

        setCenter(page);

        showMicrosoftPanel();
    }

    private VBox createAccountCards() {
        VBox cards = new VBox(16);
        cards.setPrefWidth(270);
        cards.setMinWidth(240);

        microsoftCard = createAccountCard(
                "▦",
                "Microsoft",
                "Account",
                true
        );

        localCard = createAccountCard(
                "♙",
                "Local Account",
                "",
                false
        );

        microsoftCard.setOnAction(event -> {
            microsoftSelected = true;
            updateCardStyles();
            showMicrosoftPanel();
        });

        localCard.setOnAction(event -> {
            microsoftSelected = false;
            updateCardStyles();
            showLocalPanel();
        });

        cards.getChildren().addAll(
                microsoftCard,
                localCard
        );

        return cards;
    }

    private Button createAccountCard(
            String icon,
            String firstLine,
            String secondLine,
            boolean selected
    ) {
        VBox content = new VBox(18);
        content.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("account-card-icon");

        VBox image = new VBox(iconLabel);
        image.setAlignment(Pos.CENTER);
        image.getStyleClass().add("account-card-image");
        image.setPrefSize(145, 175);
        image.setMinSize(145, 175);
        image.setMaxSize(145, 175);

        Label title = new Label(
                secondLine.isBlank()
                        ? firstLine
                        : firstLine + "\n" + secondLine
        );
        title.getStyleClass().add("account-card-title");
        title.setWrapText(true);

        content.getChildren().addAll(image, title);

        Button card = new Button();
        card.setGraphic(content);
        card.getStyleClass().add("account-choice");
        card.setPrefWidth(160);
        card.setPrefHeight(250);
        card.setAlignment(Pos.TOP_CENTER);

        return card;
    }

    private void updateCardStyles() {
        microsoftCard.getStyleClass().remove("account-choice-selected");
        localCard.getStyleClass().remove("account-choice-selected");

        if (microsoftSelected) {
            microsoftCard.getStyleClass().add("account-choice-selected");
        } else {
            localCard.getStyleClass().add("account-choice-selected");
        }
    }

    private void showMicrosoftPanel() {
        rightPanel.getChildren().clear();
        rightPanel.setSpacing(18);

        Label section = new Label("MICROSOFT ACCOUNT");
        section.getStyleClass().add("account-section-heading");

        VBox information = new VBox(8);
        information.getStyleClass().add("account-information");
        information.setPadding(new Insets(20));

        Label title = new Label(
                minecraftAccount == null
                        ? "Connect your Minecraft account"
                        : minecraftAccount.username()
        );
        title.getStyleClass().add("account-name");

        Label description = new Label(
                minecraftAccount == null
                        ? "Sign in with Microsoft to play Minecraft Java Edition."
                        : "Your Microsoft account is connected."
        );
        description.getStyleClass().add("description");
        description.setWrapText(true);

        information.getChildren().addAll(title, description);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button action = new Button(
                minecraftAccount == null
                        ? "▦   Sign in with Microsoft"
                        : "Sign in completed"
        );
        action.getStyleClass().add("microsoft-button");
        action.setMaxWidth(Double.MAX_VALUE);
        action.setPrefHeight(54);
        action.setDisable(minecraftAccount != null);

        action.setOnAction(event -> microsoftLogin.accept(null));

        rightPanel.getChildren().addAll(
                section,
                information,
                spacer,
                action
        );
        VBox.setVgrow(spacer, Priority.ALWAYS);
    }

    private void showLocalPanel() {
        rightPanel.getChildren().clear();
        rightPanel.setSpacing(18);

        Label section = new Label("LOCAL ACCOUNT");
        section.getStyleClass().add("account-section-heading");

        VBox information = new VBox(12);
        information.getStyleClass().add("account-information");
        information.setPadding(new Insets(20));

        Label title = new Label(
                localAccountName == null
                        ? "Create a Local Account"
                        : "Current Local Account: " + localAccountName
        );
        title.getStyleClass().add("account-name");

        Label description = new Label(
                "Create an offline launcher profile using a username. "
                        + "This does not provide premium Minecraft authentication."
        );
        description.getStyleClass().add("description");
        description.setWrapText(true);

        TextField username = new TextField();
        username.setPromptText("Enter username (3-16 characters)");
        username.getStyleClass().add("account-input");

        if (localAccountName != null) {
            username.setText(localAccountName);
        }

        information.getChildren().addAll(
                title,
                description,
                username
        );

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button action = new Button(
                localAccountName == null
                        ? "Create Local Account"
                        : "Save Local Account"
        );
        action.getStyleClass().add("microsoft-button");
        action.setMaxWidth(Double.MAX_VALUE);
        action.setPrefHeight(54);

        action.setOnAction(event -> {
            String name = username.getText().trim();

            if (!name.matches("[A-Za-z0-9_]{3,16}")) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Invalid username");
                alert.setHeaderText(null);
                alert.setContentText(
                        "Username must be 3-16 characters and contain "
                                + "only letters, numbers or underscore."
                );
                alert.show();
                return;
            }

            localAccount.accept(name);
        });

        rightPanel.getChildren().addAll(
                section,
                information,
                spacer,
                action
        );
        VBox.setVgrow(spacer, Priority.ALWAYS);
    }
}
