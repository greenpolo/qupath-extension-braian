// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.utils;

import qupath.lib.projects.ProjectIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ProjectDiscoveryService {
    private ProjectDiscoveryService() {
    }

    /**
     * Discover QuPath project files in the immediate subdirectories of {@code rootPath}.
     *
     * <p>Discovery rule: include {@code <subdir>/project.<ext>} where {@code <ext>} is
     * {@link ProjectIO#DEFAULT_PROJECT_EXTENSION}.
     */
    public static List<Path> discoverProjectFiles(Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return List.of();
        }

        String extension = ProjectIO.DEFAULT_PROJECT_EXTENSION;
        List<Path> results = new ArrayList<>();

        try (var dirStream = Files.list(rootPath)) {
            dirStream
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        Path projectFile = dir.resolve("project." + extension);
                        if (Files.exists(projectFile)) {
                            results.add(projectFile);
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }

        results.sort(Comparator.comparing(path -> path.getParent().getFileName().toString()));
        return results;
    }
}
