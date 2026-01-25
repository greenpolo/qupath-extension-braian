// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.gui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.braian.config.ProjectsConfig;

import java.util.List;
import java.util.Objects;

public class ExperimentPane extends VBox {
    private final VBox channelStack = new VBox(12);
    private final ObservableList<String> channelNames;
    private final List<String> availableImageChannels;

    public ExperimentPane(ProjectsConfig config,
                          ObservableList<String> channelNames,
                          List<String> availableImageChannels,
                          Stage owner,
                          BooleanProperty batchMode,
                          Runnable onPreview,
                          Runnable onRun) {
        this.channelNames = channelNames;
        this.availableImageChannels = availableImageChannels;
        Runnable previewAction = Objects.requireNonNullElse(onPreview, () -> {});
        Runnable runAction = Objects.requireNonNullElse(onRun, () -> {});

        setSpacing(16);
        setPadding(new Insets(16));

        getChildren().addAll(
                buildGlobalSection(),
                new Separator(),
                buildChannelSection(),
                new Separator(),
                buildCommandBar(batchMode, previewAction, runAction)
        );
    }

    private VBox buildGlobalSection() {
        VBox section = new VBox(8);
        Label header = new Label("Global Experiment Settings");
        TextField classForDetectionsField = new TextField();
        classForDetectionsField.setPromptText("Restrict analysis to this annotation class (optional)");
        section.getChildren().addAll(header, classForDetectionsField);
        return section;
    }

    private VBox buildChannelSection() {
        VBox section = new VBox(12);
        Label header = new Label("Channel Configuration");
        channelStack.setSpacing(12);
        Button addButton = new Button("+ Add Channel");
        addButton.setOnAction(event -> addChannelCard());
        section.getChildren().addAll(header, channelStack, addButton);
        return section;
    }

    private HBox buildCommandBar(BooleanProperty batchMode, Runnable onPreview, Runnable onRun) {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_RIGHT);
        Button previewButton = new Button("Preview on Current Image");
        Button runButton = new Button("Run Analysis");
        runButton.textProperty().bind(Bindings.when(batchMode).then("Run Batch Analysis").otherwise("Run Analysis"));
        previewButton.setOnAction(event -> onPreview.run());
        runButton.setOnAction(event -> onRun.run());
        HBox.setHgrow(runButton, Priority.NEVER);
        bar.getChildren().addAll(previewButton, runButton);
        return bar;
    }

    private void addChannelCard() {
        ChannelCard card = new ChannelCard(availableImageChannels);
        card.setOnRemove(() -> channelStack.getChildren().remove(card));
        channelStack.getChildren().add(card);
    }
}
