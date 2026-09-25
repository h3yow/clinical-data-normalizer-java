package com.github.h3yow.clinicaldatanormalizerjava.hl7;

import java.util.List;

public record Hl7Message(List<Hl7Segment> segments, String fieldSeparator,
                         String encodingCharacters, String charset) {

    public List<Hl7Segment> segments(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).toList();
    }

    public Hl7Segment first(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }
}
