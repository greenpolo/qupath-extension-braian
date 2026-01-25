// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.gui;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.ext.braian.config.AutoThresholdParmameters;
import qupath.ext.braian.config.ChannelClassifierConfig;
import qupath.ext.braian.config.ChannelDetectionsConfig;
import qupath.ext.braian.config.WatershedCellDetectionConfig;
import qupath.lib.gui.dialogs.Dialogs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class ChannelCard extends VBox {
    private final ChannelDetectionsConfig config;
    private final WatershedCellDetectionConfig params;
    private final Stage owner;
    private final Supplier<Path> configRootSupplier;
    private final Supplier<Path> projectDirSupplier;
    private final Runnable onConfigChanged;
    private final Runnable onChannelNameChanged;
    private final BooleanSupplier isUpdatingSupplier;
    private final VBox classifierList = new VBox(6);
    private final List<ChannelClassifierConfig> classifiers;
    private Runnable onRemove = () -> {};

    public ChannelCard(ChannelDetectionsConfig config,
                       List<String> availableChannels,
                       Stage owner,
                       Supplier<Path> configRootSupplier,
                       Supplier<Path> projectDirSupplier,
                       Runnable onConfigChanged,
                       Runnable onChannelNameChanged,
                       BooleanSupplier isUpdatingSupplier) {
        this.config = config;
        this.params = config.getParameters();
        this.owner = owner;
        this.configRootSupplier = configRootSupplier;
        this.projectDirSupplier = projectDirSupplier;
        this.onConfigChanged = onConfigChanged;
        this.onChannelNameChanged = onChannelNameChanged;
        this.isUpdatingSupplier = isUpdatingSupplier;
        this.classifiers = new ArrayList<>(Optional.ofNullable(config.getClassifiers()).orElse(List.of()));

        setSpacing(12);
        setPadding(new Insets(12));

        ComboBox<String> channelName = new ComboBox<>(FXCollections.observableArrayList(availableChannels));
        channelName.setEditable(true);
        channelName.setPromptText("Channel name");
        channelName.setValue(config.getName());
        channelName.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            String name = value != null ? value.trim() : null;
            if (name != null && name.isEmpty()) {
                name = null;
            }
            config.setName(name);
            if (name != null) {
                config.setClassifiers(new ArrayList<>(classifiers));
            }
            notifyChannelNameChanged();
            notifyConfigChanged();
        });

        Spinner<Integer> channelIdSpinner = createIntegerSpinner(1, 16, config.getInputChannelID(), 1);
        channelIdSpinner.setPrefWidth(60);
        channelIdSpinner.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            config.setInputChannelID(value);
            notifyConfigChanged();
        });

        Button removeButton = new Button("Remove");
        removeButton.setOnAction(event -> onRemove.run());

        HBox header = new HBox(8, new Label("Source Ch"), channelIdSpinner, new Label("-> Name"), channelName, removeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(channelName, Priority.ALWAYS);

        ToggleGroup thresholdGroup = new ToggleGroup();
        RadioButton autoThreshold = new RadioButton("Auto");
        RadioButton manualThreshold = new RadioButton("Manual");
        autoThreshold.setToggleGroup(thresholdGroup);
        manualThreshold.setToggleGroup(thresholdGroup);

        Spinner<Double> thresholdSpinner = createDoubleSpinner(0, 65535, 1, params.getThreshold());
        thresholdSpinner.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setThreshold(value);
            notifyConfigChanged();
        });

        VBox manualBox = new VBox(6, new Label("Threshold"), thresholdSpinner);

        VBox autoBox = buildAutoThresholdBox();

        boolean autoEnabled = params.getHistogramThreshold() != null;
        autoThreshold.setSelected(autoEnabled);
        manualThreshold.setSelected(!autoEnabled);

        autoBox.managedProperty().bind(autoThreshold.selectedProperty());
        autoBox.visibleProperty().bind(autoThreshold.selectedProperty());
        manualBox.managedProperty().bind(manualThreshold.selectedProperty());
        manualBox.visibleProperty().bind(manualThreshold.selectedProperty());

        autoThreshold.selectedProperty().addListener((obs, oldValue, selected) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            if (selected) {
                ensureAutoThreshold();
            } else {
                params.setHistogramThreshold(null);
            }
            notifyConfigChanged();
        });

        HBox thresholdMode = new HBox(12, new Label("Threshold"), autoThreshold, manualThreshold);
        thresholdMode.setAlignment(Pos.CENTER_LEFT);

        GridPane standardGrid = new GridPane();
        standardGrid.setHgap(12);
        standardGrid.setVgap(8);
        standardGrid.add(thresholdMode, 0, 0, 2, 1);
        standardGrid.add(manualBox, 0, 1, 2, 1);
        standardGrid.add(autoBox, 0, 2, 2, 1);

        Spinner<Double> pixelSize = createDoubleSpinner(0.1, 10.0, 0.1, params.getRequestedPixelSizeMicrons());
        pixelSize.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setRequestedPixelSizeMicrons(value);
            notifyConfigChanged();
        });
        addGridRow(standardGrid, 0, 3, "Pixel size (µm)", pixelSize);

        Spinner<Double> sigma = createDoubleSpinner(0.0, 5.0, 0.5, params.getSigmaMicrons());
        sigma.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setSigmaMicrons(value);
            notifyConfigChanged();
        });
        addGridRow(standardGrid, 0, 4, "Sigma", sigma);

        Spinner<Double> backgroundRadius = createDoubleSpinner(0.0, 100.0, 1.0, params.getBackgroundRadiusMicrons());
        backgroundRadius.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setBackgroundRadiusMicrons(value);
            notifyConfigChanged();
        });
        addGridRow(standardGrid, 0, 5, "Background radius", backgroundRadius);

        Spinner<Double> minArea = createDoubleSpinner(0.0, 5000.0, 1.0, params.getMinAreaMicrons());
        minArea.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setMinAreaMicrons(value);
            notifyConfigChanged();
        });
        Spinner<Double> maxArea = createDoubleSpinner(0.0, 10000.0, 1.0, params.getMaxAreaMicrons());
        maxArea.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setMaxAreaMicrons(value);
            notifyConfigChanged();
        });
        addGridRow(standardGrid, 0, 6, "Min area", minArea);
        addGridRow(standardGrid, 0, 7, "Max area", maxArea);

        VBox standardSection = new VBox(8, new Label("Standard parameters"), standardGrid);

        TitledPane advancedPane = new TitledPane("Advanced parameters", buildAdvancedSection());
        advancedPane.setExpanded(false);

        TitledPane classifiersPane = new TitledPane("Classifiers", buildClassifierSection(channelName));
        classifiersPane.setExpanded(false);

        getChildren().addAll(header, standardSection, advancedPane, classifiersPane);
    }

    public void setOnRemove(Runnable onRemove) {
        this.onRemove = Objects.requireNonNullElse(onRemove, () -> {});
    }

    private void addGridRow(GridPane grid, int col, int row, String label, Node control) {
        Label lbl = new Label(label);
        grid.add(lbl, col, row);
        grid.add(control, col + 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
    }

    private Spinner<Double> createDoubleSpinner(double min, double max, double step, double value) {
        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
        spinner.setEditable(true);
        return spinner;
    }

    private Spinner<Integer> createIntegerSpinner(int min, int max, int value, int step) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value, step));
        spinner.setEditable(true);
        return spinner;
    }

    private AutoThresholdParmameters ensureAutoThreshold() {
        AutoThresholdParmameters paramsAuto = params.getHistogramThreshold();
        if (paramsAuto == null) {
            paramsAuto = new AutoThresholdParmameters();
            params.setHistogramThreshold(paramsAuto);
        }
        return paramsAuto;
    }

    private VBox buildAutoThresholdBox() {
        AutoThresholdParmameters autoParams = params.getHistogramThreshold();
        if (autoParams == null) {
            autoParams = new AutoThresholdParmameters();
        }

        Spinner<Integer> resolutionLevel = createIntegerSpinner(0, 10, autoParams.getResolutionLevel(), 1);
        resolutionLevel.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            ensureAutoThreshold().setResolutionLevel(value);
            notifyConfigChanged();
        });

        Spinner<Integer> smoothWindowSize = createIntegerSpinner(1, 100, autoParams.getSmoothWindowSize(), 1);
        smoothWindowSize.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            ensureAutoThreshold().setSmoothWindowSize(value);
            notifyConfigChanged();
        });

        Spinner<Double> peakProminence = createDoubleSpinner(1, 10000, 10, autoParams.getPeakProminence());
        peakProminence.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            ensureAutoThreshold().setPeakProminence(value);
            notifyConfigChanged();
        });

        int nPeakValue = Math.max(1, autoParams.getnPeak() + 1);
        Spinner<Integer> nPeak = createIntegerSpinner(1, 10, nPeakValue, 1);
        nPeak.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            ensureAutoThreshold().setnPeak(value);
            notifyConfigChanged();
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        addGridRow(grid, 0, 0, "Resolution", resolutionLevel);
        addGridRow(grid, 0, 1, "Smooth window", smoothWindowSize);
        addGridRow(grid, 0, 2, "Peak prominence", peakProminence);
        addGridRow(grid, 0, 3, "Peak index", nPeak);
        return new VBox(8, new Label("Auto-threshold parameters"), grid);
    }

    private VBox buildAdvancedSection() {
        CheckBox backgroundReconstruction = new CheckBox("Background by reconstruction");
        backgroundReconstruction.setSelected(params.isBackgroundByReconstruction());
        backgroundReconstruction.selectedProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setBackgroundByReconstruction(value);
            notifyConfigChanged();
        });

        Spinner<Double> medianRadius = createDoubleSpinner(0.0, 20.0, 0.5, params.getMedianRadiusMicrons());
        medianRadius.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setMedianRadiusMicrons(value);
            notifyConfigChanged();
        });

        CheckBox watershed = new CheckBox("Watershed split");
        watershed.setSelected(params.isWatershedPostProcess());
        watershed.selectedProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setWatershedPostProcess(value);
            notifyConfigChanged();
        });

        Spinner<Double> cellExpansion = createDoubleSpinner(0.0, 50.0, 1.0, params.getCellExpansionMicrons());
        cellExpansion.valueProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setCellExpansionMicrons(value);
            notifyConfigChanged();
        });

        CheckBox includeNuclei = new CheckBox("Include nuclei");
        includeNuclei.setSelected(params.isIncludeNuclei());
        includeNuclei.selectedProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setIncludeNuclei(value);
            notifyConfigChanged();
        });

        CheckBox smoothBoundaries = new CheckBox("Smooth boundaries");
        smoothBoundaries.setSelected(params.isSmoothBoundaries());
        smoothBoundaries.selectedProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setSmoothBoundaries(value);
            notifyConfigChanged();
        });

        CheckBox makeMeasurements = new CheckBox("Make measurements");
        makeMeasurements.setSelected(params.isMakeMeasurements());
        makeMeasurements.selectedProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            params.setMakeMeasurements(value);
            notifyConfigChanged();
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        addGridRow(grid, 0, 0, "Median radius", medianRadius);
        addGridRow(grid, 0, 1, "Cell expansion (µm)", cellExpansion);

        VBox box = new VBox(8,
                backgroundReconstruction,
                watershed,
                grid,
                includeNuclei,
                smoothBoundaries,
                makeMeasurements
        );
        return box;
    }

    private VBox buildClassifierSection(ComboBox<String> channelName) {
        classifierList.getChildren().clear();
        for (ChannelClassifierConfig classifier : classifiers) {
            classifierList.getChildren().add(buildClassifierRow(classifier));
        }

        Button addClassifier = new Button("+ Add Classifier");
        addClassifier.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            String name = channelName.getValue();
            return name == null || name.isBlank();
        }, channelName.valueProperty()));
        addClassifier.setOnAction(event -> addClassifierFromChooser());

        VBox section = new VBox(8, classifierList, addClassifier);
        return section;
    }

    private Node buildClassifierRow(ChannelClassifierConfig classifier) {
        String baseName = classifier.getName();
        String fileName = baseName == null || baseName.isBlank() ? "(unnamed).json" : baseName + ".json";
        Label nameLabel = new Label(fileName);
        nameLabel.setMinWidth(160);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        TextField annotations = new TextField(formatAnnotations(classifier.getAnnotationsToClassify()));
        annotations.setPromptText("Restrict to regions (optional)");
        annotations.textProperty().addListener((obs, oldValue, value) -> {
            if (isUpdatingSupplier.getAsBoolean()) {
                return;
            }
            List<String> parsed = parseAnnotations(value);
            classifier.setAnnotationsToClassify(parsed.isEmpty() ? null : parsed);
            config.setClassifiers(new ArrayList<>(classifiers));
            notifyConfigChanged();
        });

        Button remove = new Button("Remove");
        HBox row = new HBox(8, nameLabel, annotations, remove);
        remove.setOnAction(event -> {
            classifiers.remove(classifier);
            config.setClassifiers(new ArrayList<>(classifiers));
            classifierList.getChildren().remove(row);
            notifyConfigChanged();
        });
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(annotations, Priority.ALWAYS);
        return row;
    }

    private void addClassifierFromChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select classifier");
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

        ChannelClassifierConfig classifier = new ChannelClassifierConfig();
        classifier.setName(stripJsonExtension(selectedPath.getFileName().toString()));
        classifiers.add(classifier);
        config.setClassifiers(new ArrayList<>(classifiers));
        classifierList.getChildren().add(buildClassifierRow(classifier));
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

    private List<String> parseAnnotations(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                results.add(trimmed);
            }
        }
        return results;
    }

    private String formatAnnotations(List<String> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return "";
        }
        return String.join(", ", annotations);
    }

    private void notifyConfigChanged() {
        if (!isUpdatingSupplier.getAsBoolean()) {
            onConfigChanged.run();
        }
    }

    private void notifyChannelNameChanged() {
        if (!isUpdatingSupplier.getAsBoolean()) {
            onChannelNameChanged.run();
        }
    }
}
