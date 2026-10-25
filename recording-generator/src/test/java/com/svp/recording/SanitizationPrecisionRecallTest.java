package com.svp.recording;

import com.svp.engine.json.Json;
import com.svp.engine.testsupport.EvalOutput;
import com.svp.recording.generate.StubConfig;
import com.svp.recording.sanitize.Detection;
import com.svp.recording.sanitize.SanitizedLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-level precision/recall on the labelled golden dataset (QUALITY_CRITERIA 2).
 * A field is "masked" when its sanitised value differs from the original; every unlabelled field
 * must come through unchanged (value and type). Writes target/eval/sanitization-results.json.
 */
class SanitizationPrecisionRecallTest {

    private final SanitizerStubGenerator pipeline = new SanitizerStubGenerator();

    @Test
    void meetsPrecisionRecallAndZeroLeakGates() {
        List<GoldenDataset.Record> records = GoldenDataset.load();
        assertThat(records).hasSize(40);

        Map<String, int[]> perCategory = new TreeMap<>(); // [labelled, truePositive, falsePositive]
        List<String> falseNegatives = new ArrayList<>();
        List<String> falsePositives = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        int fields = 0;

        for (GoldenDataset.Record r : records) {
            Map<String, Object> original = LogFlattener.flatten(r.log());
            SanitizedLog sanitized = pipeline.sanitize(r.log());
            Map<String, Object> after = LogFlattener.flatten(sanitized);
            Map<String, String> detectedCategory = new LinkedHashMap<>();
            for (Detection d : sanitized.detections()) {
                detectedCategory.putIfAbsent(d.field(), d.type().name().equals("SSN") || d.type().name().equals("NATIONAL_ID")
                        ? "PII_CRITICAL" : d.category().name());
            }
            for (String labelled : r.sensitiveFields()) {
                assertThat(original).as(r.id() + " label refers to a real field: " + labelled).containsKey(labelled);
            }
            fields += original.size();

            for (Map.Entry<String, Object> e : original.entrySet()) {
                String field = e.getKey();
                boolean changed = !Objects.equals(e.getValue(), after.get(field));
                boolean sensitive = r.sensitiveFields().contains(field);
                if (sensitive) {
                    String category = r.fieldCategories().get(field);
                    int[] c = perCategory.computeIfAbsent(category, k -> new int[3]);
                    c[0]++;
                    if (changed) {
                        c[1]++;
                    } else {
                        falseNegatives.add(r.id() + " " + field);
                    }
                } else if (changed) {
                    String category = detectedCategory.getOrDefault(field, "UNKNOWN");
                    perCategory.computeIfAbsent(category, k -> new int[3])[2]++;
                    falsePositives.add(r.id() + " " + field + " (" + category + ") " + e.getValue() + " -> " + after.get(field));
                }
            }

            // zero-leak gate over everything the pipeline emits for this exchange
            StubConfig config = pipeline.generate("golden", List.of(r.log()));
            String emitted = Json.write(config) + Json.write(sanitized);
            for (String field : r.sensitiveFields()) {
                for (String secret : secretFragments(String.valueOf(original.get(field)))) {
                    if (emitted.contains(secret)) {
                        leaks.add(r.id() + " " + field + " leaked '" + secret + "'");
                    }
                }
            }
        }

        int labelled = perCategory.values().stream().mapToInt(c -> c[0]).sum();
        int tp = perCategory.values().stream().mapToInt(c -> c[1]).sum();
        int fp = falsePositives.size();
        double recall = tp / (double) labelled;
        double precision = tp / (double) (tp + fp);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("records", records.size());
        report.put("fields_evaluated", fields);
        report.put("labelled_sensitive_fields", labelled);
        report.put("true_positives", tp);
        report.put("false_positives", fp);
        report.put("false_negatives", falseNegatives.size());
        report.put("precision", precision);
        report.put("recall", recall);
        Map<String, Object> cats = new LinkedHashMap<>();
        perCategory.forEach((k, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("labelled", c[0]);
            m.put("true_positives", c[1]);
            m.put("false_positives", c[2]);
            m.put("recall", c[0] == 0 ? null : c[1] / (double) c[0]);
            m.put("precision", c[1] + c[2] == 0 ? null : c[1] / (double) (c[1] + c[2]));
            cats.put(k, m);
        });
        report.put("by_category", cats);
        report.put("false_negative_fields", falseNegatives);
        report.put("false_positive_fields", falsePositives);
        report.put("leaks", leaks);
        EvalOutput.write("sanitization-results", report);

        assertThat(leaks).as("sensitive values leaked into generated output").isEmpty();
        assertThat(falseNegatives).as("missed sensitive fields").isEmpty();
        assertThat(recall).isGreaterThanOrEqualTo(0.99);
        assertThat(precision).as("precision; false positives: " + falsePositives).isGreaterThanOrEqualTo(0.95);
        int[] pci = perCategory.get("PCI");
        int[] critical = perCategory.get("PII_CRITICAL");
        assertThat(pci[1]).as("PCI recall must be 100%").isEqualTo(pci[0]);
        assertThat(critical[1]).as("SSN / national id recall must be 100%").isEqualTo(critical[0]);
    }

    /** Substrings that must never appear in output: the value itself and its long tokens. */
    private static List<String> secretFragments(String value) {
        List<String> out = new ArrayList<>();
        String compact = value.replace(" ", "").replace("-", "");
        if (value.length() >= 6) {
            out.add(value);
        }
        if (compact.length() >= 12 && compact.chars().allMatch(Character::isDigit)) {
            out.add(compact); // a PAN must not survive in any layout
        }
        for (String token : value.split("[\\s;,=]+")) {
            if (token.length() >= 8 && (token.contains("@") || token.chars().filter(Character::isDigit).count() >= 6
                    || token.startsWith("eyJ") || token.length() >= 20)) {
                out.add(token);
            }
        }
        return out;
    }
}
