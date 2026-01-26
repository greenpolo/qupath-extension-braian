// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.gui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.cell.CheckBoxListCell;
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
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.braian.config.ProjectsConfig;
import qupath.ext.braian.runners.ABBAImporterRunner;
import qupath.ext.braian.runners.BraiAnAnalysisRunner;
import qupath.ext.braian.utils.BraiAn;
import qupath.ext.braian.utils.ProjectDiscoveryService;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;
import org.yaml.snakeyaml.error.YAMLException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BraiAnDetectDialog {
    private static final Logger logger = LoggerFactory.getLogger(BraiAnDetectDialog.class);
    private static final String CONFIG_FILENAME = "BraiAn.yml";

    private final QuPathGUI qupath;
    private final Stage stage;
    private final BooleanProperty batchMode = new SimpleBooleanProperty(false);
    private final BooleanProperty batchReady = new SimpleBooleanProperty(false);
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final TextField batchRootField = new TextField();
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(500));
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService runExecutor = Executors.newSingleThreadExecutor();
    private final ObservableList<String> channelNames = FXCollections.observableArrayList();
    private final ObservableList<DiscoveredProject> discoveredProjects = FXCollections.observableArrayList();
    private final List<String> availableImageChannels = new ArrayList<>();
    private ProjectsConfig config;
    private Path configPath;
    private ExperimentPane experimentPane;
    private Runnable onClose;

    private static final class DiscoveredProject {
        private final String name;
        private final Path projectFile;
        private final BooleanProperty selected = new SimpleBooleanProperty(true);

        private DiscoveredProject(String name, Path projectFile, boolean selected) {
            this.name = name;
            this.projectFile = projectFile;
            this.selected.set(selected);
        }

        public Path getProjectFile() {
            return projectFile;
        }

        public BooleanProperty selectedProperty() {
            return selected;
        }

        public boolean isSelected() {
            return selected.get();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum InitialTab {
        IMPORT,
        DETECTION
    }

    public BraiAnDetectDialog(QuPathGUI qupath, InitialTab initialTab) {
        this.qupath = qupath;
        this.stage = new Stage();
        this.stage.setTitle("BraiAnDetect Pipeline Manager");
        this.stage.initOwner(qupath.getStage());
        this.stage.setWidth(980);
        this.stage.setHeight(820);
        this.batchReady.bind(batchRootField.textProperty().isNotEmpty());
        this.stage.setOnHidden(event -> {
            shutdownExecutors();
            if (onClose != null) {
                onClose.run();
            }
        });

        initializeConfig();

        // Update context if the project changes while the dialog is open
        qupath.projectProperty().addListener((obs, oldValue, newValue) -> {
            Platform.runLater(this::updateConfigForContext);
        });
        // We do not rebuild root on project change anymore to avoid resetting tab state
        this.stage.setScene(new Scene(buildRoot(initialTab)));
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
        loadConfig(resolveConfigPath());
        ImageData<BufferedImage> imageData = qupath.getViewer() != null ? qupath.getViewer().getImageData() : null;
        if (imageData != null) {
            for (ImageChannel channel : imageData.getServerMetadata().getChannels()) {
                availableImageChannels.add(channel.getName());
            }
        }
    }

    private Parent buildRoot(InitialTab initialTab) {
        TabPane tabPane = new TabPane();
        Tab importTab = buildImportTab();
        Tab experimentTab = buildExperimentTab();

        tabPane.getTabs().addAll(importTab, experimentTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        if (initialTab == InitialTab.DETECTION) {
            tabPane.getSelectionModel().select(experimentTab);
        } else {
            tabPane.getSelectionModel().select(importTab);
        }

        BorderPane root = new BorderPane(tabPane);
        root.setPadding(new Insets(8));
        return root;
    }

    private Tab buildImportTab() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(16));

        VBox actionPanel = new VBox(8);
        Button importCurrentButton = new Button("Import to Current Image");
        Button importProjectButton = new Button("Import to Project");
        Button importExperimentButton = new Button("Import to Experiment");

        importCurrentButton.disableProperty().bind(running);
        importProjectButton.disableProperty().bind(running);
        importExperimentButton.disableProperty().bind(running);

        importCurrentButton.setOnAction(event -> handleImportCurrent());
        importProjectButton.setOnAction(event -> handleImportProject());
        importExperimentButton.setOnAction(event -> handleImportExperiment());

        actionPanel.getChildren().addAll(importCurrentButton, importProjectButton, importExperimentButton);

        VBox projectListPanel = buildProjectListPanel();

        Label warningLabel = new Label(
                "Note: Importing atlas annotations will clear all existing objects in the hierarchy.");
        warningLabel.getStyleClass().add("warning");

        container.getChildren().addAll(actionPanel, projectListPanel, warningLabel);
        return new Tab("Atlas Import", container);
    }

    private Tab buildExperimentTab() {
        VBox scopeSection = new VBox(12);
        scopeSection.setPadding(new Insets(16, 16, 0, 16));

        HBox scopeRow = new HBox(16);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup scopeGroup = new ToggleGroup();
        RadioButton currentProjectToggle = new RadioButton("Current Project");
        RadioButton batchToggle = new RadioButton("Experiment");
        currentProjectToggle.setToggleGroup(scopeGroup);
        batchToggle.setToggleGroup(scopeGroup);
        currentProjectToggle.setSelected(true);
        scopeRow.getChildren().addAll(new Label("Scope:"), currentProjectToggle, batchToggle);

        currentProjectToggle.selectedProperty().addListener((obs, oldValue, selected) -> {
            if (selected) {
                batchMode.set(false);
                updateConfigForContext();
            }
        });
        batchToggle.selectedProperty().addListener((obs, oldValue, selected) -> {
            if (selected) {
                batchMode.set(true);
                updateConfigForContext();
                refreshDiscoveredProjects();
            }
        });

        HBox batchChooserRow = new HBox(8);
        batchChooserRow.setAlignment(Pos.CENTER_LEFT);
        batchRootField.setPromptText("Select a root folder containing QuPath projects");
        batchRootField.setEditable(true);
        batchRootField.textProperty().addListener((obs, oldValue, value) -> {
            if (batchMode.get()) {
                updateConfigForContext();
                refreshDiscoveredProjects();
            }
        });
        Button browseButton = new Button("Browse");
        browseButton.setOnAction(event -> chooseBatchFolder(stage));
        HBox.setHgrow(batchRootField, Priority.ALWAYS);
        batchChooserRow.getChildren().addAll(new Label("Projects Folder:"), batchRootField, browseButton);
        batchChooserRow.managedProperty().bind(batchMode);
        batchChooserRow.visibleProperty().bind(batchMode);

        VBox projectListPanel = buildProjectListPanel();
        scopeSection.getChildren().addAll(scopeRow, batchChooserRow, projectListPanel, new Separator());

        this.experimentPane = new ExperimentPane(
                config,
                channelNames,
                availableImageChannels,
                stage,
                batchMode,
                batchReady,
                running,
                this::handlePreview,
                this::handleRun,
                this::scheduleConfigSave,
                this::resolveConfigRoot,
                this::resolveProjectDirectory);
        ScrollPane scrollPane = new ScrollPane(experimentPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        BorderPane layout = new BorderPane();
        layout.setTop(scopeSection);
        layout.setCenter(scrollPane);
        return new Tab("Analysis & Detection", layout);
    }

    private void handleImportCurrent() {
        runAsync("ABBA Import", () -> {
            ABBAImporterRunner.runCurrentImage(qupath);
            Platform.runLater(() -> experimentPane.autoDetectAtlas());
        });
    }

    private void handleImportProject() {
        runAsync("ABBA Import", () -> {
            ABBAImporterRunner.runProject(qupath);
            Platform.runLater(() -> experimentPane.autoDetectAtlas());
        });
    }

    private void handleImportExperiment() {
        Path rootPath = resolveBatchRoot();
        if (rootPath == null) {
            chooseBatchFolder(stage);
            rootPath = resolveBatchRoot();
        }
        if (rootPath == null) {
            Dialogs.showErrorMessage("BraiAnDetect", "Select a projects folder before importing to an experiment.");
            return;
        }

        refreshDiscoveredProjects();
        List<Path> selectedProjects = getSelectedProjectFiles();
        if (selectedProjects.isEmpty()) {
            Dialogs.showErrorMessage("BraiAnDetect", "Select at least one project to import.");
            return;
        }
        runAsync("ABBA Import", () -> ABBAImporterRunner.runBatch(qupath, selectedProjects));
    }

    private void handlePreview() {
        if (!flushConfigNow()) {
            return;
        }
        runAsync("Preview", () -> BraiAnAnalysisRunner.runPreview(qupath));
    }

    private void handleRun() {
        if (!flushConfigNow()) {
            return;
        }
        if (batchMode.get()) {
            Path rootPath = resolveBatchRoot();
            if (rootPath == null) {
                Dialogs.showErrorMessage("BraiAnDetect", "Select a projects folder before running batch analysis.");
                return;
            }
            List<Path> selectedProjects = getSelectedProjectFiles();
            if (selectedProjects.isEmpty()) {
                Dialogs.showErrorMessage("BraiAnDetect", "Select at least one project to analyze.");
                return;
            }
            runAsync("Run Batch Analysis", () -> BraiAnAnalysisRunner.runBatch(qupath, rootPath, selectedProjects));
        } else {
            runAsync("Run Analysis", () -> BraiAnAnalysisRunner.runProject(qupath));
        }
    }

    private VBox buildProjectListPanel() {
        ListView<DiscoveredProject> listView = new ListView<>(discoveredProjects);
        listView.setCellFactory(CheckBoxListCell.forListView(DiscoveredProject::selectedProperty));
        listView.setPrefHeight(160);
        listView.managedProperty().bind(batchMode);
        listView.visibleProperty().bind(batchMode);

        Label label = new Label("Discovered projects");
        label.managedProperty().bind(batchMode);
        label.visibleProperty().bind(batchMode);

        VBox panel = new VBox(8, label, listView);
        panel.managedProperty().bind(batchMode);
        panel.visibleProperty().bind(batchMode);
        return panel;
    }

    private void refreshDiscoveredProjects() {
        Path rootPath = resolveBatchRoot();
        if (rootPath == null) {
            discoveredProjects.clear();
            return;
        }

        Map<Path, Boolean> selectedByPath = new HashMap<>();
        for (DiscoveredProject project : discoveredProjects) {
            selectedByPath.put(project.getProjectFile(), project.isSelected());
        }

        // Run discovery in background to avoid freezing UI
        runAsync("Discover Projects", () -> {
            List<Path> projectFiles = ProjectDiscoveryService.discoverProjectFiles(rootPath);
            List<DiscoveredProject> refreshed = new ArrayList<>();
            for (Path projectFile : projectFiles) {
                String name = projectFile.getParent().getFileName().toString();
                boolean selected = selectedByPath.getOrDefault(projectFile, true);
                refreshed.add(new DiscoveredProject(name, projectFile, selected));
            }
            Platform.runLater(() -> discoveredProjects.setAll(refreshed));
        });
    }

    private List<Path> getSelectedProjectFiles() {
        return discoveredProjects.stream()
                .filter(DiscoveredProject::isSelected)
                .map(DiscoveredProject::getProjectFile)
                .toList();
    }

    private boolean flushConfigNow() {
        saveDebounce.stop();
        if (configPath == null) {
            Dialogs.showErrorMessage("BraiAnDetect",
                    "No configuration path is available. Select a valid project or batch folder.");
            return false;
        }
        try {
            writeConfigNow(ProjectsConfig.toYaml(config));
            return true;
        } catch (IOException e) {
            Dialogs.showErrorMessage("BraiAnDetect", "Failed to save BraiAn.yml: " + e.getMessage());
            return false;
        }
    }

    private void scheduleConfigSave() {
        saveDebounce.stop();
        saveDebounce.setOnFinished(event -> saveConfigAsync(ProjectsConfig.toYaml(config)));
        saveDebounce.playFromStart();
    }

    private void saveConfigAsync(String yaml) {
        if (configPath == null) {
            logger.warn("Config path is not available; skipping save.");
            return;
        }
        if (saveExecutor.isShutdown()) {
            return;
        }
        saveExecutor.execute(() -> {
            try {
                writeConfigNow(yaml);
            } catch (IOException e) {
                logger.error("Failed to save config", e);
            }
        });
    }

    private void writeConfigNow(String yaml) throws IOException {
        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }
        Files.writeString(configPath, yaml, StandardCharsets.UTF_8);
    }

    private void loadConfig(Path path) {
        if (path == null) {
            return;
        }
        ProjectsConfig loadedConfig;
        try {
            if (Files.exists(path)) {
                loadedConfig = ProjectsConfig.read(path);
            } else {
                loadedConfig = new ProjectsConfig();
            }
        } catch (IOException | YAMLException e) {
            Dialogs.showErrorMessage("BraiAnDetect", "Unable to load BraiAn.yml: " + e.getMessage());
            loadedConfig = new ProjectsConfig();
        }
        this.config = loadedConfig;
        this.configPath = path;
        if (experimentPane != null) {
            experimentPane.setConfig(loadedConfig);
        }
        if (!Files.exists(path)) {
            saveConfigAsync(ProjectsConfig.toYaml(loadedConfig));
        }
    }

    private void updateConfigForContext() {
        Path resolved = resolveConfigPath();
        if (resolved == null) {
            return;
        }
        if (configPath != null && configPath.equals(resolved)) {
            return;
        }
        loadConfig(resolved);
    }

    private Path resolveConfigPath() {
        if (batchMode.get()) {
            String root = batchRootField.getText();
            if (root == null || root.isBlank()) {
                return null;
            }
            return Path.of(root).resolve(CONFIG_FILENAME);
        }
        Optional<Path> configPathOpt = BraiAn.resolvePathIfPresent(CONFIG_FILENAME);
        if (configPathOpt.isPresent()) {
            return configPathOpt.get();
        }
        Path projectDir = resolveProjectDirectory();
        if (projectDir == null) {
            return null;
        }
        return projectDir.resolve(CONFIG_FILENAME);
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

    private Path resolveConfigRoot() {
        if (configPath != null) {
            return configPath.getParent();
        }
        return resolveProjectDirectory();
    }

    private Path resolveBatchRoot() {
        String root = batchRootField.getText();
        if (root == null || root.isBlank()) {
            return null;
        }
        Path path = Path.of(root);
        if (!Files.isDirectory(path)) {
            return null;
        }
        return path;
    }

    private void runAsync(String title, Runnable task) {
        if (running.get() || runExecutor.isShutdown()) {
            return;
        }
        running.set(true);
        runExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("{} failed", title, e);
                showError(title, e);
            } finally {
                Platform.runLater(() -> running.set(false));
            }
        });
    }

    private void showError(String title, Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = "Unexpected error. See log for details.";
        }
        String finalMessage = message;
        Platform.runLater(() -> Dialogs.showErrorMessage(title, finalMessage));
    }

    private void shutdownExecutors() {
        saveDebounce.stop();
        saveExecutor.shutdown();
        runExecutor.shutdown();
    }

    private Path resolveProjectDirectory() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return null;
        }
        return Projects.getBaseDirectory(project).toPath();
    }
}
