// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.gui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.braian.config.ProjectsConfig;
import qupath.ext.braian.utils.BraiAn;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BraiAnDetectDialog {
    private static final Logger logger = LoggerFactory.getLogger(BraiAnDetectDialog.class);
    private static final String CONFIG_FILENAME = "BraiAn.yml";

    private final QuPathGUI qupath;
    private final Stage stage;
    private final BooleanProperty batchMode = new SimpleBooleanProperty(false);
    private final TextField batchRootField = new TextField();
    private final ObservableList<String> channelNames = FXCollections.observableArrayList();
    private final List<String> availableImageChannels = new ArrayList<>();
    private ProjectsConfig config;
    private Path configPath;
    private ExperimentPane experimentPane;
    private Runnable onClose;

    public BraiAnDetectDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.stage = new Stage();
        this.stage.setTitle("BraiAnDetect Pipeline Manager");
        this.stage.setWidth(980);
        this.stage.setHeight(820);
        this.stage.setOnHidden(event -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        initializeConfig();
        this.stage.setScene(new Scene(buildRoot()));
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void show() {
        this.stage.show();
        this.stage.toFront();
    }

    private void initializeConfig() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("BraiAnDetect", "Open a project before launching the GUI.");
            throw new IllegalStateException("No project open");
        }
        Optional<Path> configPathOpt = BraiAn.resolvePathIfPresent(CONFIG_FILENAME);
        try {
            if (configPathOpt.isPresent()) {
                this.config = ProjectsConfig.read(CONFIG_FILENAME);
                this.configPath = configPathOpt.get();
            } else {
                this.config = new ProjectsConfig();
                this.configPath = Projects.getBaseDirectory(project).toPath().resolve(CONFIG_FILENAME);
            }
        } catch (IOException e) {
            Dialogs.showErrorMessage("BraiAnDetect", "Unable to load BraiAn.yml: " + e.getMessage());
            this.config = new ProjectsConfig();
            this.configPath = Projects.getBaseDirectory(project).toPath().resolve(CONFIG_FILENAME);
        }
        ImageData<BufferedImage> imageData = qupath.getViewer() != null ? qupath.getViewer().getImageData() : null;
        if (imageData != null) {
            for (ImageChannel channel : imageData.getServerMetadata().getChannels()) {
                availableImageChannels.add(channel.getName());
            }
        }
    }

    private BorderPane buildRoot() {
        TabPane tabs = new TabPane();
        tabs.getTabs().add(buildImportTab());
        tabs.getTabs().add(buildExperimentTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane(tabs);
        root.setPadding(new Insets(8));
        return root;
    }

    private Tab buildImportTab() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(16));

        HBox scopeRow = new HBox(16);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup scopeGroup = new ToggleGroup();
        RadioButton currentProjectToggle = new RadioButton("Current Project");
        RadioButton batchToggle = new RadioButton("Batch Experiment");
        currentProjectToggle.setToggleGroup(scopeGroup);
        batchToggle.setToggleGroup(scopeGroup);
        currentProjectToggle.setSelected(true);
        scopeRow.getChildren().addAll(new Label("Scope:"), currentProjectToggle, batchToggle);

        batchToggle.selectedProperty().addListener((obs, oldValue, selected) -> batchMode.set(selected));

        HBox batchChooserRow = new HBox(8);
        batchChooserRow.setAlignment(Pos.CENTER_LEFT);
        batchRootField.setPromptText("Select a root folder containing QuPath projects");
        batchRootField.setEditable(true);
        Button browseButton = new Button("Browse");
        browseButton.setOnAction(event -> chooseBatchFolder(batchRootField.getScene().getWindow()));
        HBox.setHgrow(batchRootField, Priority.ALWAYS);
        batchChooserRow.getChildren().addAll(new Label("Projects Folder:"), batchRootField, browseButton);
        batchChooserRow.managedProperty().bind(batchMode);
        batchChooserRow.visibleProperty().bind(batchMode);

        VBox actionPanel = new VBox(8);
        Button importCurrentButton = new Button("Import Atlas to Current Image");
        Button importBatchButton = new Button("Import Atlas to Selected Projects");
        importBatchButton.disableProperty().bind(Bindings.or(batchMode.not(), batchRootField.textProperty().isEmpty()));

        importCurrentButton.setOnAction(event -> handleImportCurrent());
        importBatchButton.setOnAction(event -> handleImportBatch());
        actionPanel.getChildren().addAll(importCurrentButton, importBatchButton);

        Label warningLabel = new Label("Note: Importing atlas annotations will clear all existing objects in the hierarchy.");
        warningLabel.getStyleClass().add("warning");

        container.getChildren().addAll(scopeRow, batchChooserRow, new Separator(), actionPanel, warningLabel);
        return new Tab("Setup & Import", container);
    }

    private Tab buildExperimentTab() {
        this.experimentPane = new ExperimentPane(
                config,
                channelNames,
                availableImageChannels,
                stage,
                batchMode,
                this::handlePreview,
                this::handleRun
        );
        ScrollPane scrollPane = new ScrollPane(experimentPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        return new Tab("Analysis & Detection", scrollPane);
    }

    private void handleImportCurrent() {
        logger.info("ABBA import (current image) is not wired yet.");
    }

    private void handleImportBatch() {
        logger.info("ABBA batch import is not wired yet.");
    }

    private void handlePreview() {
        logger.info("Preview run is not wired yet.");
    }

    private void handleRun() {
        logger.info("Run analysis is not wired yet.");
    }

    private void chooseBatchFolder(Window owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select QuPath Projects Folder");
        Path projectDir = resolveProjectDirectory();
        if (projectDir != null && Files.exists(projectDir)) {
            chooser.setInitialDirectory(projectDir.toFile());
        }
        var selection = chooser.showDialog(owner);
        if (selection != null) {
            batchRootField.setText(selection.getAbsolutePath());
        }
    }

    private Path resolveProjectDirectory() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return null;
        }
        return Projects.getBaseDirectory(project).toPath();
    }
}
