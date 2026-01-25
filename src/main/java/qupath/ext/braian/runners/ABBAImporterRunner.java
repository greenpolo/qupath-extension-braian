// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.runners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.braian.config.ProjectsConfig;
import qupath.fx.utils.FXUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.Commands;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ABBAImporterRunner {
    private static final Logger logger = LoggerFactory.getLogger(ABBAImporterRunner.class);
    private static final String ATLAS_NAME = "allen_mouse_10um_java";
    private static final String NAMING_PROPERTY = "acronym";
    private static final boolean SPLIT_LEFT_RIGHT = true;
    private static final boolean OVERWRITE = true;

    private ABBAImporterRunner() {
    }

    public static void runCurrentImage(QuPathGUI qupath) {
        if (!AbbaReflectionBridge.isAvailable()) {
            throw new IllegalStateException(AbbaReflectionBridge.getFailureReason());
        }
        ImageData<BufferedImage> imageData = qupath.getViewer() != null ? qupath.getViewer().getImageData() : null;
        if (imageData == null) {
            throw new IllegalStateException("No image is currently open.");
        }
        
        // Load config from project or default
        ProjectsConfig config = null;
        if (qupath.getProject() != null) {
            try {
                config = ProjectsConfig.read("BraiAn.yml");
            } catch (Exception e) {
                 logger.warn("Could not load BraiAn.yml, using default atlas.");
            }
        }
        String atlasName = (config != null) ? config.getAtlasName() : "allen_mouse_10um_java";
        
        importAtlas(imageData, atlasName);
        Project<BufferedImage> project = qupath.getProject();
        if (project != null) {
            ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
            if (entry != null) {
                try {
                    entry.saveImageData(imageData);
                } catch (IOException e) {
                    logger.warn("Failed to save image data after import: {}", e.getMessage());
                }
            }
        }
    }

    public static void runBatch(QuPathGUI qupath, Path rootPath) {
        if (!AbbaReflectionBridge.isAvailable()) {
            throw new IllegalStateException(AbbaReflectionBridge.getFailureReason());
        }
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Invalid projects directory: " + rootPath);
        }
        List<Path> projectFiles = findProjectFiles(rootPath);
        if (projectFiles.isEmpty()) {
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

            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                ImageData<BufferedImage> imageData;
                try {
                    imageData = entry.readImageData();
                } catch (IOException e) {
                    logger.error("Failed to read image data {}: {}", entry.getImageName(), e.getMessage());
                    continue;
                }
                try {
                    QPEx.setBatchProjectAndImage(project, imageData);
                    importAtlas(imageData, config.getAtlasName());
                    entry.saveImageData(imageData);
                } catch (Exception e) {
                    logger.error("Failed to import atlas for {}: {}", entry.getImageName(), e.getMessage());
                } finally {
                    closeServer(imageData);
                }
            }

            try {
                project.syncChanges();
            } catch (Exception e) {
                logger.warn("Failed to sync project {}: {}", projectFile, e.getMessage());
            }
            System.gc();
            FXUtils.runOnApplicationThread(() -> Commands.closeProject(qupath));
        }
    }

    private static void importAtlas(ImageData<BufferedImage> imageData, String atlasName) {
        imageData.setImageType(ImageData.ImageType.FLUORESCENCE);
        imageData.getHierarchy().clearAll();
        if (atlasName == null || atlasName.isBlank()) {
           atlasName = "allen_mouse_10um_java";
        }
        AbbaReflectionBridge.loadWarpedAtlasAnnotations(imageData, atlasName, NAMING_PROPERTY, SPLIT_LEFT_RIGHT, OVERWRITE);
    }

    private static void closeServer(ImageData<BufferedImage> imageData) {
        try {
            imageData.getServer().close();
        } catch (Exception e) {
            logger.warn("Failed to close image server: {}", e.getMessage());
        }
    }

    private static List<Path> findProjectFiles(Path rootPath) {
        List<Path> projectFiles = new ArrayList<>();
        try (var dirStream = Files.list(rootPath)) {
            dirStream.filter(Files::isDirectory).forEach(dir -> resolveProjectFile(dir).ifPresent(projectFiles::add));
        } catch (IOException e) {
            logger.warn("Failed to scan projects directory {}: {}", rootPath, e.getMessage());
        }
        return projectFiles;
    }

    private static java.util.Optional<Path> resolveProjectFile(Path dir) {
        String extension = ProjectIO.DEFAULT_PROJECT_EXTENSION;
        Path defaultProject = dir.resolve("project." + extension);
        if (Files.exists(defaultProject)) {
            return java.util.Optional.of(defaultProject);
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith("." + extension)).findFirst();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }
}
