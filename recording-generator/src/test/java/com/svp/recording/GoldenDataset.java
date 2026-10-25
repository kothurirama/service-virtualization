package com.svp.recording;

import com.fasterxml.jackson.core.type.TypeReference;
import com.svp.engine.json.Json;

import java.util.List;
import java.util.Map;

/** Loader for {@code sanitization_golden_dataset.json}. */
public final class GoldenDataset {

    public record Record(String id, String description, RawTrafficLog log, List<String> sensitiveFields,
                         Map<String, String> fieldCategories) {
    }

    private record File(String dataset, String description, List<Record> records) {
    }

    private GoldenDataset() {
    }

    public static List<Record> load() {
        return Json.read(GoldenDataset.class.getClassLoader().getResourceAsStream("sanitization_golden_dataset.json"),
                new TypeReference<File>() { }).records();
    }
}
