// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.runners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.braian.AtlasManager;
import qupath.ext.braian.AbstractDetections;
import qupath.ext.braian.ChannelDetections;
import qupath.ext.braian.ImageChannelTools;
import qupath.ext.braian.NoCellContainersFoundException;
import qupath.ext.braian.OverlappingDetections;
import qupath.ext.braian.config.ChannelClassifierConfig;
import qupath.ext.braian.config.ChannelDetectionsConfig;
import qupath.ext.braian.config.ProjectsConfig;
import qupath.ext.braian.PartialClassifier;
import qupath.ext.braian.utils.ProjectDiscoveryService;
import qupath.fx.utils.FXUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.Commands;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class BraiAnAnalysisRunner {
    private static final Logger logger = LoggerFactory.getLogger(BraiAnAnalysisRunner.class);
    private static final String CONFIG_FILENAME = "BraiAn.yml";

    private BraiAnAnalysisRunner() {
    }

    public static void runPreview(QuPathGUI qupath) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            throw new IllegalStateException("No project open.");
        }
        ProjectsConfig config = loadConfigForProject();
        ImageData<BufferedImage> imageData = qupath.getViewer() != null ? qupath.getViewer().getImageData() : null;
        if (imageData == null) {
            throw new IllegalStateException("No image open.");
        }
        processImage(qupath, imageData, project, null, config, false);
    }

    public static void runProject(QuPathGUI qupath) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            throw new IllegalStateException("No project open.");
        }
        ProjectsConfig config = loadConfigForProject();
        runProjectImages(qupath, project, config, true);
    }

    public static void runBatch(QuPathGUI qupath, Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Invalid projects directory: " + rootPath);
        }
        List<Path> projectFiles = ProjectDiscoveryService.discoverProjectFiles(rootPath);
        runBatch(qupath, rootPath, projectFiles);
    }

    public static void runBatch(QuPathGUI qupath, Path rootPath, List<Path> projectFiles) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Invalid projects directory: " + rootPath);
        }
        ProjectsConfig config = loadConfig(rootPath.resolve(CONFIG_FILENAME));
        if (projectFiles == null || projectFiles.isEmpty()) {
            throw new IllegalStateException("No QuPath projects found in " + rootPath);
        }

        for (Path projectFile : projectFiles) {
            Project<BufferedImage> project;
            try {
                project = ProjectIO.loadProject(projectFile.toFile(), BufferedImage.class);
            } catch (IOException e) {
                logger.error("Failed to load project {}: {}", projectFile, e.getMessage());
                continue;
            }
            FXUtils.runOnApplicationThread(() -> qupath.setProject(project));
            runProjectImages(qupath, project, config, true);
            FXUtils.runOnApplicationThread(() -> Commands.closeProject(qupath));
        }
    }

    private static void runProjectImages(QuPathGUI qupath,
            Project<BufferedImage> project,
            ProjectsConfig config,
            boolean export) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            ImageData<BufferedImage> imageData;
            try {
                imageData = entry.readImageData();
            } catch (IOException e) {
                logger.error("Failed to read image data {}: {}", entry.getImageName(), e.getMessage());
                continue;
            }
            try {
                processImage(qupath, imageData, project, entry, config, export);
                entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.error("Failed processing {}: {}", entry.getImageName(), e.getMessage());
            } finally {
                closeServer(imageData);
            }
        }
        try {
            project.syncChanges();
        } catch (Exception e) {
            logger.warn("Failed to sync project {}: {}", project.getName(), e.getMessage());
        }
        System.gc();
    }

    private static void processImage(QuPathGUI qupath,
            ImageData<BufferedImage> imageData,
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> entry,
            ProjectsConfig config,
            boolean export) {
        applyChannelRenaming(imageData, config);

        var hierarchy = imageData.getHierarchy();
        Collection<PathAnnotationObject> annotations = config.getAnnotationsForDetections(hierarchy);

        List<ChannelDetections> allDetections = new ArrayList<>();
        List<ChannelDetectionsConfig> channelConfigs = Optional.ofNullable(config.getChannelDetections())
                .orElse(List.of());
        for (ChannelDetectionsConfig detectionsConfig : channelConfigs) {
            String name = detectionsConfig.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                ImageChannelTools channel = new ImageChannelTools(name, imageData);
                ChannelDetections detections = new ChannelDetections(channel, annotations,
                        detectionsConfig.getParameters(), hierarchy, imageData, project, qupath);
                allDetections.add(detections);
            } catch (IllegalArgumentException e) {
                logger.warn("Skipping {}: {}", name, e.getMessage());
            } catch (NoCellContainersFoundException e) {
                logger.warn("No detections found for {}", name);
            }
        }

        if (allDetections.isEmpty()) {
            String label = entry != null ? entry.getImageName() : imageData.getServerMetadata().getName();
            logger.info("{}: no detections computed", label);
            return;
        }

        for (ChannelDetections detections : allDetections) {
            ChannelDetectionsConfig detectionsConfig = channelConfigs.stream()
                    .filter(conf -> detections.getId().equals(conf.getName()))
                    .findFirst()
                    .orElse(null);
            if (detectionsConfig == null || detectionsConfig.getClassifiers() == null) {
                continue;
            }
            List<PartialClassifier<BufferedImage>> partialClassifiers = new ArrayList<>();
            for (ChannelClassifierConfig classifierConfig : detectionsConfig.getClassifiers()) {
                try {
                    partialClassifiers.add(classifierConfig.toPartialClassifier(hierarchy, project));
                } catch (IOException e) {
                    logger.warn("Failed to load classifier {}: {}", classifierConfig.getName(), e.getMessage());
                }
            }
            detections.applyClassifiers(partialClassifiers, imageData);
        }

        List<OverlappingDetections> overlaps = new ArrayList<>();
        config.getControlChannel().ifPresent(controlName -> {
            ChannelDetections control = allDetections.stream()
                    .filter(det -> det.getId().equals(controlName))
                    .findFirst()
                    .orElse(null);
            if (control == null) {
                return;
            }
            List<ChannelDetections> others = allDetections.stream()
                    .filter(det -> !det.getId().equals(controlName))
                    .toList();
            if (others.isEmpty()) {
                return;
            }
            try {
                List<AbstractDetections> otherDetections = new ArrayList<>(others);
                overlaps.add(new OverlappingDetections(control, otherDetections, true, hierarchy, project, qupath));
            } catch (NoCellContainersFoundException e) {
                logger.warn("Unable to compute overlaps: {}", e.getMessage());
            }
        });

        if (!export || project == null || entry == null) {
            return;
        }

        String atlasName = config.getAtlasName();
        if (atlasName == null) {
            atlasName = "allen_mouse_10um_java";
        }

        if (!AtlasManager.isImported(atlasName, hierarchy)) {
            logger.warn("No atlas '{}' imported for {}", atlasName, entry.getImageName());
            return;
        }

            try {
                AtlasManager atlas = new AtlasManager(atlasName, hierarchy);
                atlas.fixExclusions();
                String imageName = sanitizeFileName(entry.getImageName());
                Path projectDir = Projects.getBaseDirectory(project).toPath();
                Path resultsPath = projectDir.resolve("results").resolve(imageName + "_regions.tsv");
                Path exclusionsPath = projectDir.resolve("regions_to_exclude")
                        .resolve(imageName + "_regions_to_exclude.txt");
                atlas.saveResults(concat(allDetections, overlaps), resultsPath.toFile(), imageData, entry);
                atlas.saveExcludedRegions(exclusionsPath.toFile());
            } catch (RuntimeException e) {
                logger.error("Failed to export results for {}: {}", entry.getImageName(), e.getMessage());
            }
    }

    private static ProjectsConfig loadConfigForProject() {
        try {
            return ProjectsConfig.read(CONFIG_FILENAME);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load BraiAn.yml: " + e.getMessage(), e);
        }
    }

    private static ProjectsConfig loadConfig(Path configPath) {
        try {
            return ProjectsConfig.read(configPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load BraiAn.yml: " + e.getMessage(), e);
        }
    }

    private static void closeServer(ImageData<BufferedImage> imageData) {
        try {
            imageData.getServer().close();
        } catch (Exception e) {
            logger.warn("Failed to close image server: {}", e.getMessage());
        }
    }

    private static List<AbstractDetections> concat(List<ChannelDetections> detections,
            List<OverlappingDetections> overlaps) {
        List<AbstractDetections> merged = new ArrayList<>(detections);
        for (OverlappingDetections overlap : overlaps) {
            merged.add(overlap);
        }
        return merged;
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "image";
        }
        String sanitized = raw.replaceAll("[<>:\"/\\\\|?*]", "");
        if (sanitized.isBlank()) {
            return "image";
        }
        return sanitized;
    }

    private static void applyChannelRenaming(ImageData<BufferedImage> imageData, ProjectsConfig config) {
        var channelConfigs = config.getChannelDetections();
        if (channelConfigs == null || channelConfigs.isEmpty()) {
            return;
        }

        var metadata = imageData.getServerMetadata();
        var channels = metadata.getChannels();
        List<String> currentNames = new ArrayList<>();
        for (var ch : channels) {
            currentNames.add(ch.getName());
        }

        boolean changed = false;
        for (ChannelDetectionsConfig chConfig : channelConfigs) {
            int inputId = chConfig.getInputChannelID(); // 1-based
            String targetName = chConfig.getName();

            if (inputId > 0 && inputId <= currentNames.size() && targetName != null && !targetName.isBlank()) {
                // Check if rename is needed
                int index = inputId - 1;
                if (!currentNames.get(index).equals(targetName)) {
                    currentNames.set(index, targetName);
                    changed = true;
                }
            }
        }

        if (changed) {
            try {
                var server = imageData.getServer();
                var oldMetadata = server.getMetadata();
                var oldChannels = oldMetadata.getChannels();

                // Build new channel list with updated names
                List<qupath.lib.images.servers.ImageChannel> newChannels = new ArrayList<>();
                for (int i = 0; i < oldChannels.size(); i++) {
                    var oldChannel = oldChannels.get(i);
                    String newName = currentNames.get(i);
                    newChannels.add(qupath.lib.images.servers.ImageChannel.getInstance(newName, oldChannel.getColor()));
                }

                // Create new metadata with updated channels
                var newMetadata = new qupath.lib.images.servers.ImageServerMetadata.Builder(oldMetadata)
                        .channels(newChannels)
                        .build();
                imageData.updateServerMetadata(newMetadata);
                logger.info("Updated channel names to: {}", currentNames);
            } catch (Exception e) {
                logger.error("Failed to update channel names: {}", e.getMessage());
            }
        }
    }

    // Project file discovery is delegated to ProjectDiscoveryService.
}
