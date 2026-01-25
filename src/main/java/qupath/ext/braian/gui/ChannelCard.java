// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.gui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

public class ChannelCard extends VBox {
    private Runnable onRemove = () -> {};

    public ChannelCard(List<String> availableChannels) {
        setSpacing(8);
        setPadding(new Insets(12));

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> channelName = new ComboBox<>(FXCollections.observableArrayList(availableChannels));
        channelName.setEditable(true);
        channelName.setPromptText("Channel name");
        Button removeButton = new Button("Remove");
        removeButton.setOnAction(event -> onRemove.run());
        HBox.setHgrow(channelName, Priority.ALWAYS);
        header.getChildren().addAll(new Label("Channel"), channelName, removeButton);

        VBox standardSection = new VBox(6);
        standardSection.getChildren().addAll(
                new Label("Standard parameters will appear here."),
                new Separator(),
                new Label("Classifiers will appear here.")
        );

        TitledPane advanced = new TitledPane("Advanced Parameters", new Label("Advanced parameters will appear here."));
        advanced.setExpanded(false);

        getChildren().addAll(header, standardSection, advanced);
    }

    public void setOnRemove(Runnable onRemove) {
        this.onRemove = Objects.requireNonNullElse(onRemove, () -> {});
    }
}
