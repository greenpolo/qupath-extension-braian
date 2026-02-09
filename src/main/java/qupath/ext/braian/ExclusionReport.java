//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Report entry describing one automatically-excluded atlas region.
 */
public record ExclusionReport(
        Path projectFile,
        String projectName,
        String imageName,
        UUID excludedAnnotationId,
        String regionName,
        String channelName,
        double meanIntensity,
        int otsuThreshold
) {
    public ExclusionReport {
        Objects.requireNonNull(imageName, "imageName");
        Objects.requireNonNull(excludedAnnotationId, "excludedAnnotationId");
        Objects.requireNonNull(channelName, "channelName");
    }

    public String imageLabel() {
        if (projectName != null && !projectName.isBlank()) {
            return projectName + ": " + imageName;
        }
        return imageName;
    }
}
