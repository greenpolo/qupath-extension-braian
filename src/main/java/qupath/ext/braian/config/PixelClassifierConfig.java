// SPDX-FileCopyrightText: 2026 OpenAI Assistant
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package qupath.ext.braian.config;

import java.util.List;

public class PixelClassifierConfig {
    private String classifierName;
    private String measurementId;
    private List<String> regionFilter;

    public String getClassifierName() {
        return classifierName;
    }

    public void setClassifierName(String classifierName) {
        this.classifierName = classifierName;
    }

    public String getMeasurementId() {
        return measurementId;
    }

    public void setMeasurementId(String measurementId) {
        this.measurementId = measurementId;
    }

    public List<String> getRegionFilter() {
        return regionFilter;
    }

    public void setRegionFilter(List<String> regionFilter) {
        this.regionFilter = regionFilter;
    }
}
