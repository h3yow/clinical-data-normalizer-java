package com.github.h3yow.clinicaldatanormalizerjava.export;

public record DeidentifiedObservation(
        String category,
        String code,
        String displayName,
        double value,
        String unit,
        int effectiveYear
) {
}
