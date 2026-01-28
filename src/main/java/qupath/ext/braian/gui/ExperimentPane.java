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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.ext.braian.config.AutoThresholdParmameters;
import qupath.ext.braian.config.ChannelDetectionsConfig;
import qupath.ext.braian.config.DetectionsCheckConfig;
import qupath.ext.braian.config.PixelClassifierConfig;
import qupath.ext.braian.config.ProjectsConfig;
import qupath.ext.braian.config.WatershedCellDetectionConfig;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class ExperimentPane extends VBox {
    private final ObservableList<String> channelNames;
    private final List<String> availableImageChannels;
    private final Stage owner;
    private final BooleanProperty batchMode;
    private final BooleanProperty batchReady;
    private final BooleanProperty running;
    private final Runnable onPreview;
    private final Runnable onRun;
    private final Runnable onConfigChanged;
    private final Supplier<Path> configRootSupplier;
    private final Supplier<Path> projectDirSupplier;
    private final Supplier<ImageData<?>> imageDataSupplier;
    private final VBox channelStack = new VBox(12);
    private final VBox pixelClassifierStack = new VBox(12);
    private final TextField classForDetectionsField = new TextField();
    private final TextField atlasNameField = new TextField();
    private final CheckBox enableCellDetectionCheckBox = new CheckBox("Enable Cell Detection");
    private final CheckBox enablePixelClassificationCheckBox = new CheckBox("Enable Pixel Classification");
    private final CheckBox detectionsCheckBox = new CheckBox("Enforce Co-localization");
    private final ComboBox<String> controlChannelCombo = new ComboBox<>();
    private final Button addChannelButton = new Button("+ Add Channel");
    private final Button addPixelClassifierButton = new Button("+ Add Pixel Classifier");
    private ProjectsConfig config;
    private boolean isUpdating = false;

    private static final String HELP_URL_CROSS_CHANNEL =
            "https://silvalab.codeberg.page/BraiAn/image-analysis/#:~:text=Find%20co%2Dlabelled%20detections";

    public ExperimentPane(ProjectsConfig config,
            ObservableList<String> channelNames,
            List<String> availableImageChannels,
            Stage owner,
            BooleanProperty batchMode,
            BooleanProperty batchReady,
            BooleanProperty running,
            Runnable onPreview,
            Runnable onRun,
            Runnable onConfigChanged,
            Supplier<Path> configRootSupplier,
            Supplier<Path> projectDirSupplier,
            Supplier<ImageData<?>> imageDataSupplier) {
        this.channelNames = channelNames;
        this.availableImageChannels = availableImageChannels;
        this.owner = owner;
        this.batchMode = batchMode;
        this.batchReady = batchReady;
        this.running = running;
        this.onPreview = Objects.requireNonNullElse(onPreview, () -> {
        });
        this.onRun = Objects.requireNonNullElse(onRun, () -> {
        });
        this.onConfigChanged = Objects.requireNonNullElse(onConfigChanged, () -> {
        });
        this.config = config;
        this.configRootSupplier = configRootSupplier;
        this.projectDirSupplier = projectDirSupplier;
        this.imageDataSupplier = imageDataSupplier;

        setSpacing(16);
        setPadding(new Insets(16));

        getChildren().addAll(
                buildGlobalSection(),
                new Separator(),
                buildChannelSection(),
                new Separator(),
                buildPixelClassifierSection(),
                new Separator(),
                buildCommandBar());

        ensureChannelListMutable();
        refreshFromConfig();
    }

    public void setConfig(ProjectsConfig config) {
        this.config = config;
        ensureChannelListMutable();
        refreshFromConfig();
    }

    private VBox buildGlobalSection() {
        VBox section = new VBox(8);
        Label header = new Label("Global Experiment Settings");
        classForDetectionsField.setPromptText("Restrict analysis to this annotation class (optional)");
        classForDetectionsField.textProperty().addListener((obs, oldValue, value) -> {
            if (isUpdating) {
                return;
            }
            String trimmed = value != null ? value.trim() : "";
            config.setClassForDetections(trimmed.isEmpty() ? null : trimmed);
            notifyConfigChanged();
        });

        atlasNameField.setPromptText("Atlas name (e.g. allen_mouse_10um_java)");
        atlasNameField.textProperty().addListener((obs, oldValue, value) -> {
            if (isUpdating) {
                return;
            }
            String trimmed = value != null ? value.trim() : "";
            config.setAtlasName(trimmed.isEmpty() ? "allen_mouse_10um_java" : trimmed);
            notifyConfigChanged();
        });

        // Auto-detect atlas from hierarchy if not already set or default
        autoDetectAtlas();

        HBox detectionsCheckRow = new HBox(12);
        detectionsCheckRow.setAlignment(Pos.CENTER_LEFT);
        detectionsCheckBox.selectedProperty().addListener((obs, oldValue, selected) -> {
            if (isUpdating) {
                return;
            }
            config.getDetectionsCheck().setApply(selected);
            notifyConfigChanged();
        });
        detectionsCheckBox.disableProperty().bind(enableCellDetectionCheckBox.selectedProperty().not());
        controlChannelCombo.setItems(channelNames);
        controlChannelCombo.setPromptText("Control channel");
        controlChannelCombo.disableProperty()
                .bind(Bindings.or(
                        enableCellDetectionCheckBox.selectedProperty().not(),
                        Bindings.or(detectionsCheckBox.selectedProperty().not(), Bindings.isEmpty(channelNames))));
        controlChannelCombo.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdating) {
                return;
            }
            config.getDetectionsCheck().setControlChannel(value);
            notifyConfigChanged();
        });
        detectionsCheckRow.getChildren().addAll(detectionsCheckBox, controlChannelCombo);

        enableCellDetectionCheckBox.selectedProperty().addListener((obs, oldValue, selected) -> {
            if (isUpdating) {
                return;
            }
            config.setEnableCellDetection(selected);
            if (!selected) {
                detectionsCheckBox.setSelected(false);
            }
            notifyConfigChanged();
        });

        enablePixelClassificationCheckBox.selectedProperty().addListener((obs, oldValue, selected) -> {
            if (isUpdating) {
                return;
            }
            config.setEnablePixelClassification(selected);
            notifyConfigChanged();
        });

        HBox toggleRow = new HBox(16, enableCellDetectionCheckBox, enablePixelClassificationCheckBox);
        toggleRow.setAlignment(Pos.CENTER_LEFT);

        HBox crossChannelRow = new HBox(6);
        crossChannelRow.setAlignment(Pos.CENTER_LEFT);
        Label crossChannelLabel = new Label("Cross-channel logic");
        Hyperlink crossChannelHelp = buildHelpLink(HELP_URL_CROSS_CHANNEL);
        crossChannelRow.getChildren().addAll(crossChannelLabel, crossChannelHelp);

        section.getChildren().addAll(header,
                new Label("Region Filter:"), classForDetectionsField,
                new Label("Atlas Name (Auto-detected):"), atlasNameField,
                new Label("Analysis Modes"), toggleRow,
                crossChannelRow, detectionsCheckRow);
        return section;
    }

    private VBox buildChannelSection() {
        VBox section = new VBox(12);
        Label header = new Label("Channel Configuration");
        channelStack.setSpacing(12);
        addChannelButton.setOnAction(event -> addChannelCard());
        addChannelButton.disableProperty().bind(running);
        section.getChildren().addAll(header, channelStack, addChannelButton);
        section.managedProperty().bind(enableCellDetectionCheckBox.selectedProperty());
        section.visibleProperty().bind(enableCellDetectionCheckBox.selectedProperty());
        return section;
    }

    private VBox buildPixelClassifierSection() {
        VBox section = new VBox(12);
        Label header = new Label("Pixel Classifiers");
        pixelClassifierStack.setSpacing(12);
        addPixelClassifierButton.setOnAction(event -> addPixelClassifierCard());
        addPixelClassifierButton.disableProperty().bind(running);
        section.getChildren().addAll(header, pixelClassifierStack, addPixelClassifierButton);
        section.managedProperty().bind(enablePixelClassificationCheckBox.selectedProperty());
        section.visibleProperty().bind(enablePixelClassificationCheckBox.selectedProperty());
        return section;
    }

    private HBox buildCommandBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_RIGHT);
        Button previewButton = new Button("Preview on Current Image");
        Button runButton = new Button("Run Analysis");
        runButton.textProperty().bind(Bindings.when(batchMode).then("Run Batch Analysis").otherwise("Run Analysis"));
        var noChannels = Bindings.isEmpty(channelNames);
        var noPixelClassifiers = Bindings.isEmpty(pixelClassifierStack.getChildren());
        var noModesEnabled = enableCellDetectionCheckBox.selectedProperty()
                .not()
                .and(enablePixelClassificationCheckBox.selectedProperty().not());
        var missingInputs = enableCellDetectionCheckBox.selectedProperty().and(noChannels)
                .or(enablePixelClassificationCheckBox.selectedProperty().and(noPixelClassifiers))
                .or(noModesEnabled);
        var batchMissing = batchMode.and(batchReady.not());
        previewButton.disableProperty().bind(running.or(missingInputs));
        runButton.disableProperty().bind(running.or(missingInputs).or(batchMissing));
        previewButton.setOnAction(event -> onPreview.run());
        runButton.setOnAction(event -> onRun.run());
        HBox.setHgrow(runButton, Priority.NEVER);
        bar.getChildren().addAll(previewButton, runButton);
        return bar;
    }

    private void refreshFromConfig() {
        isUpdating = true;
        classForDetectionsField.setText(Optional.ofNullable(config.getClassForDetections()).orElse(""));
        atlasNameField.setText(Optional.ofNullable(config.getAtlasName()).orElse("allen_mouse_10um_java"));
        enableCellDetectionCheckBox.setSelected(config.isEnableCellDetection());
        enablePixelClassificationCheckBox.setSelected(config.isEnablePixelClassification());
        DetectionsCheckConfig detectionsCheck = config.getDetectionsCheck();
        detectionsCheckBox.setSelected(detectionsCheck.getApply());
        rebuildChannelCards();
        rebuildPixelClassifierCards();
        refreshChannelNames();
        syncControlChannelSelection();
        isUpdating = false;
    }

    private void rebuildChannelCards() {
        channelStack.getChildren().clear();
        for (ChannelDetectionsConfig channelConfig : config.getChannelDetections()) {
            addChannelCard(channelConfig);
        }
    }

    private void rebuildPixelClassifierCards() {
        pixelClassifierStack.getChildren().clear();
        for (PixelClassifierConfig pixelClassifier : config.getPixelClassifiers()) {
            addPixelClassifierCard(pixelClassifier);
        }
    }

    private void addChannelCard() {
        ChannelDetectionsConfig channelConfig = new ChannelDetectionsConfig();
        WatershedCellDetectionConfig params = channelConfig.getParameters();
        params.setRequestedPixelSizeMicrons(1.0);
        params.setHistogramThreshold(new AutoThresholdParmameters());
        List<ChannelDetectionsConfig> configs = new ArrayList<>(config.getChannelDetections());
        configs.add(channelConfig);
        config.setChannelDetections(configs);
        addChannelCard(channelConfig);
        refreshChannelNames();
        notifyConfigChanged();
    }

    private void addPixelClassifierCard() {
        PixelClassifierConfig pixelClassifier = new PixelClassifierConfig();
        List<PixelClassifierConfig> configs = new ArrayList<>(config.getPixelClassifiers());
        configs.add(pixelClassifier);
        config.setPixelClassifiers(configs);
        addPixelClassifierCard(pixelClassifier);
        notifyConfigChanged();
    }

    private void addChannelCard(ChannelDetectionsConfig channelConfig) {
        ChannelCard card = new ChannelCard(
                channelConfig,
                availableImageChannels,
                owner,
                configRootSupplier,
                projectDirSupplier,
                this::notifyConfigChanged,
                this::refreshChannelNames,
                () -> isUpdating);
        card.setOnRemove(() -> removeChannelCard(card, channelConfig));
        channelStack.getChildren().add(card);
    }

    private void addPixelClassifierCard(PixelClassifierConfig pixelClassifier) {
        VBox card = buildPixelClassifierCard(pixelClassifier);
        pixelClassifierStack.getChildren().add(card);
    }

    private void removeChannelCard(ChannelCard card, ChannelDetectionsConfig channelConfig) {
        List<ChannelDetectionsConfig> configs = new ArrayList<>(config.getChannelDetections());
        configs.remove(channelConfig);
        config.setChannelDetections(configs);
        channelStack.getChildren().remove(card);
        refreshChannelNames();
        notifyConfigChanged();
    }

    private void refreshChannelNames() {
        List<String> names = config.getChannelDetections().stream()
                .map(ChannelDetectionsConfig::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        channelNames.setAll(names);
        syncControlChannelSelection();
    }

    private void syncControlChannelSelection() {
        String configured = config.getDetectionsCheck().getControlChannel();
        if (configured != null && channelNames.contains(configured)) {
            controlChannelCombo.setValue(configured);
            return;
        }
        if (!channelNames.isEmpty() && config.getDetectionsCheck().getApply()) {
            controlChannelCombo.setValue(channelNames.get(0));
            config.getDetectionsCheck().setControlChannel(controlChannelCombo.getValue());
            notifyConfigChanged();
            return;
        }
        controlChannelCombo.setValue(null);
    }

    private void ensureChannelListMutable() {
        if (config.getChannelDetections() == null) {
            config.setChannelDetections(new ArrayList<>());
        } else {
            try {
                config.getChannelDetections().add(new ChannelDetectionsConfig());
                config.getChannelDetections().remove(config.getChannelDetections().size() - 1);
            } catch (UnsupportedOperationException ex) {
                config.setChannelDetections(new ArrayList<>(config.getChannelDetections()));
            }
        }

        if (config.getPixelClassifiers() == null) {
            config.setPixelClassifiers(new ArrayList<>());
        } else {
            try {
                config.getPixelClassifiers().add(new PixelClassifierConfig());
                config.getPixelClassifiers().remove(config.getPixelClassifiers().size() - 1);
            } catch (UnsupportedOperationException ex) {
                config.setPixelClassifiers(new ArrayList<>(config.getPixelClassifiers()));
            }
        }
    }

    private void notifyConfigChanged() {
        if (!isUpdating) {
            onConfigChanged.run();
        }
    }

    private Hyperlink buildHelpLink(String url) {
        Hyperlink link = new Hyperlink("(?)");
        link.setOnAction(event -> QuPathGUI.openInBrowser(url));
        link.setFocusTraversable(false);
        return link;
    }

    private VBox buildPixelClassifierCard(PixelClassifierConfig pixelConfig) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-background-color: -fx-control-inner-background;"
                + "-fx-background-radius: 6;"
                + "-fx-border-color: -fx-box-border;"
                + "-fx-border-radius: 6;");

        TextField classifierField = new TextField();
        classifierField.setPromptText("Select a .json classifier");
        classifierField.setEditable(false);
        classifierField.setText(formatClassifierName(pixelConfig.getClassifierName()));

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(event -> choosePixelClassifier(pixelConfig, classifierField));

        HBox classifierRow = new HBox(8, new Label("Classifier"), classifierField, browseButton);
        classifierRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(classifierField, Priority.ALWAYS);

        TextField measurementField = new TextField();
        measurementField.setPromptText("Measurement ID (e.g. red_projections)");
        measurementField.setText(Optional.ofNullable(pixelConfig.getMeasurementId()).orElse(""));
        measurementField.textProperty().addListener((obs, oldValue, value) -> {
            if (isUpdating) {
                return;
            }
            String trimmed = value != null ? value.trim() : "";
            pixelConfig.setMeasurementId(trimmed.isEmpty() ? null : trimmed);
            notifyConfigChanged();
        });

        Button removeButton = new Button("Remove");
        removeButton.setOnAction(event -> {
            List<PixelClassifierConfig> configs = new ArrayList<>(config.getPixelClassifiers());
            configs.remove(pixelConfig);
            this.config.setPixelClassifiers(configs);
            pixelClassifierStack.getChildren().remove(card);
            notifyConfigChanged();
        });

        HBox measurementRow = new HBox(8, new Label("Measurement"), measurementField, removeButton);
        measurementRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(measurementField, Priority.ALWAYS);

        card.getChildren().addAll(classifierRow, measurementRow);
        return card;
    }

    private void choosePixelClassifier(PixelClassifierConfig config, TextField classifierField) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select pixel classifier");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("QuPath classifier", "*.json"));
        File selected = chooser.showOpenDialog(owner);
        if (selected == null) {
            return;
        }
        Path selectedPath = selected.toPath();
        Path targetDir = resolveClassifierTargetDir();
        if (targetDir == null) {
            Dialogs.showErrorMessage("BraiAnDetect", "No project directory is available for classifier storage.");
            return;
        }

        if (!isUnderAllowedRoot(selectedPath)) {
            boolean copy = Dialogs.showConfirmDialog(
                    "Copy classifier",
                    "Copy classifier to " + targetDir + "? BraiAn requires classifiers to be stored in the project or its parent folder."
            );
            if (!copy) {
                return;
            }
            Path targetPath = targetDir.resolve(selected.getName());
            try {
                Files.copy(selectedPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Dialogs.showErrorMessage("BraiAnDetect", "Failed to copy classifier: " + e.getMessage());
                return;
            }
            selectedPath = targetPath;
        }

        String baseName = stripJsonExtension(selectedPath.getFileName().toString());
        config.setClassifierName(baseName);
        classifierField.setText(baseName + ".json");
        notifyConfigChanged();
    }

    private Path resolveClassifierTargetDir() {
        Path configRoot = configRootSupplier.get();
        if (configRoot != null) {
            return configRoot;
        }
        return projectDirSupplier.get();
    }

    private boolean isUnderAllowedRoot(Path path) {
        for (Path root : resolveClassifierRoots()) {
            if (root != null && path.normalize().startsWith(root.normalize())) {
                return true;
            }
        }
        return false;
    }

    private List<Path> resolveClassifierRoots() {
        List<Path> roots = new ArrayList<>();
        Path configRoot = configRootSupplier.get();
        if (configRoot != null) {
            roots.add(configRoot);
        }
        Path projectDir = projectDirSupplier.get();
        if (projectDir != null && !projectDir.equals(configRoot)) {
            roots.add(projectDir);
        }
        return roots;
    }

    private String stripJsonExtension(String fileName) {
        if (fileName.toLowerCase().endsWith(".json")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    private String formatClassifierName(String classifierName) {
        if (classifierName == null || classifierName.isBlank()) {
            return "";
        }
        return classifierName + ".json";
    }

    public void autoDetectAtlas() {
        ImageData<?> imageData = imageDataSupplier.get();
        if (imageData == null) {
            return;
        }
        var root = imageData.getHierarchy().getRootObject();
        if (root != null && root.getPathClass() != null) {
            String detected = root.getPathClass().getName();
            if (config.getAtlasName() == null || config.getAtlasName().equals("allen_mouse_10um_java")) {
                config.setAtlasName(detected);
                notifyConfigChanged();
            }
        }
    }
}
