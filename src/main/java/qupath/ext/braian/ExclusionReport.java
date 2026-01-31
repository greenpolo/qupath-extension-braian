// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Immutable payload used to populate the exclusion review dialog.
 */
public record ExclusionReport(
        Path projectFile,
        String projectName,
        String imageName,
        UUID excludedAnnotationId,
        String regionName,
        double maxCoverage
) {

    public String imageLabel() {
        if (projectName != null && !projectName.isBlank()) {
            return projectName + ": " + imageName;
        }
        return imageName;
    }
}
