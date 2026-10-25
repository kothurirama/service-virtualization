package com.svp.engine.testsupport;

import com.svp.engine.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes machine-readable evaluation results to {@code target/eval/}. {@code eval_report.md} is
 * assembled from these files, so the reported numbers are the measured ones.
 */
public final class EvalOutput {

    private EvalOutput() {
    }

    public static Path write(String name, Object result) {
        try {
            Path dir = Path.of("target", "eval");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".json");
            Files.writeString(file, Json.writePretty(result));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> hardware() {
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("available_processors", Runtime.getRuntime().availableProcessors());
        hw.put("max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        hw.put("java_version", System.getProperty("java.version"));
        hw.put("java_vm", System.getProperty("java.vm.name"));
        hw.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        hw.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> !a.startsWith("-javaagent")).toList());
        return hw;
    }
}
