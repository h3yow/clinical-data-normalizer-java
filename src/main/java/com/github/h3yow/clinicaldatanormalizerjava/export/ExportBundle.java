package com.github.h3yow.clinicaldatanormalizerjava.export;

import java.util.List;

public record ExportBundle(
        String pseudonym,
        long dataVersion,
        List<String> scopes,
        List<DeidentifiedObservation> observations,
        String digest
) {
}
