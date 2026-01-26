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
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.braian.config.AutoThresholdParmameters;
import qupath.ext.braian.config.ChannelDetectionsConfig;
import qupath.ext.braian.config.DetectionsCheckConfig;
import qupath.ext.braian.config.ProjectsConfig;
import qupath.ext.braian.config.WatershedCellDetectionConfig;

import java.nio.file.Path;
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
    private final VBox channelStack = new VBox(12);
    private final TextField classForDetectionsField = new TextField();
    private final TextField atlasNameField = new TextField();
    private final CheckBox detectionsCheckBox = new CheckBox("Enforce Co-localization");
    private final ComboBox<String> controlChannelCombo = new ComboBox<>();
    private final Button addChannelButton = new Button("+ Add Channel");
    private ProjectsConfig config;
    private boolean isUpdating = false;

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
            Supplier<Path> projectDirSupplier) {
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

        setSpacing(16);
        setPadding(new Insets(16));

        getChildren().addAll(
                buildGlobalSection(),
                new Separator(),
                buildChannelSection(),
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
        controlChannelCombo.setItems(channelNames);
        controlChannelCombo.setPromptText("Control channel");
        controlChannelCombo.disableProperty()
                .bind(Bindings.or(detectionsCheckBox.selectedProperty().not(), Bindings.isEmpty(channelNames)));
        controlChannelCombo.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdating) {
                return;
            }
            config.getDetectionsCheck().setControlChannel(value);
            notifyConfigChanged();
        });
        detectionsCheckRow.getChildren().addAll(detectionsCheckBox, controlChannelCombo);

        section.getChildren().addAll(header,
                new Label("Region Filter:"), classForDetectionsField,
                new Label("Atlas Name (Auto-detected):"), atlasNameField,
                new Label("Cross-channel logic"), detectionsCheckRow);
        return section;
    }

    private VBox buildChannelSection() {
        VBox section = new VBox(12);
        Label header = new Label("Channel Configuration");
        channelStack.setSpacing(12);
        addChannelButton.setOnAction(event -> addChannelCard());
        addChannelButton.disableProperty().bind(running);
        section.getChildren().addAll(header, channelStack, addChannelButton);
        return section;
    }

    private HBox buildCommandBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_RIGHT);
        Button previewButton = new Button("Preview on Current Image");
        Button runButton = new Button("Run Analysis");
        runButton.textProperty().bind(Bindings.when(batchMode).then("Run Batch Analysis").otherwise("Run Analysis"));
        var noChannels = Bindings.isEmpty(channelNames);
        var batchMissing = batchMode.and(batchReady.not());
        previewButton.disableProperty().bind(running.or(noChannels));
        runButton.disableProperty().bind(running.or(noChannels).or(batchMissing));
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
        DetectionsCheckConfig detectionsCheck = config.getDetectionsCheck();
        detectionsCheckBox.setSelected(detectionsCheck.getApply());
        rebuildChannelCards();
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
            return;
        }
        try {
            config.getChannelDetections().add(new ChannelDetectionsConfig());
            config.getChannelDetections().remove(config.getChannelDetections().size() - 1);
        } catch (UnsupportedOperationException ex) {
            config.setChannelDetections(new ArrayList<>(config.getChannelDetections()));
        }
    }

    private void notifyConfigChanged() {
        if (!isUpdating) {
            onConfigChanged.run();
        }
    }

    public void autoDetectAtlas() {
        if (qupath.lib.gui.QuPathGUI.getInstance() != null) {
            var viewer = qupath.lib.gui.QuPathGUI.getInstance().getViewer();
            if (viewer != null && viewer.getImageData() != null) {
                var root = viewer.getImageData().getHierarchy().getRootObject();
                if (root != null && root.getPathClass() != null) {
                    String detected = root.getPathClass().getName();
                    if (config.getAtlasName() == null || config.getAtlasName().equals("allen_mouse_10um_java")) {
                        config.setAtlasName(detected);
                        notifyConfigChanged();
                    }
                }
            }
        }
    }
}
