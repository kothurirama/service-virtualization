package com.svp.platform.service;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.svp.engine.StubRegistry;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;
import com.svp.platform.config.SvpProperties;
import com.svp.platform.web.ConflictException;
import com.svp.platform.web.NotFoundException;
import com.svp.recording.RawTrafficLog;
import com.svp.recording.SanitizerStubGenerator;
import com.svp.recording.generate.StubConfig;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recording workflow: ingest → sanitise → generate → (auto-publish | hold for review) → approve/reject.
 *
 * <p>Auto-publication is allowed only when the generator raised nothing for review (every detection
 * at or above the 0.90 confidence threshold, every stub compiled, no conflicting recordings).
 * Recorded stubs never overwrite hand-written or seeded stubs.
 */
@Service
public class RecordingService {

    public enum Status { PUBLISHED, PENDING_REVIEW, DRAFT, REJECTED }

    /** A generated configuration and its publication state. */
    public record RecordingView(@JsonUnwrapped StubConfig config, Status status, List<String> publishedStubIds,
                                String statusReason, Instant decidedAt) {
    }

    private final SanitizerStubGenerator generator;
    private final StubRegistry registry;
    private final Clock clock;
    private final int maxStored;
    private final Map<String, RecordingView> store = new LinkedHashMap<>();

    public RecordingService(SanitizerStubGenerator generator, StubRegistry registry, Clock clock, SvpProperties props) {
        this.generator = generator;
        this.registry = registry;
        this.clock = clock;
        this.maxStored = props.recording().maxStoredConfigs();
    }

    public synchronized RecordingView ingest(String serviceName, List<RawTrafficLog> logs, boolean autoPublish) {
        if (logs == null || logs.isEmpty()) {
            throw new IllegalArgumentException("logs must contain at least one recorded exchange");
        }
        StubConfig config = generator.generate(serviceName, logs);
        config = config.withStubConfigId(uniqueId(config.stubConfigId()));

        RecordingView view;
        if (config.sanitizationSummary().requiresHumanReview()) {
            view = new RecordingView(config, Status.PENDING_REVIEW, List.of(),
                    config.reviewQueue().size() + (config.reviewQueue().size() == 1 ? " item needs" : " items need") + " human review before publication.", null);
        } else if (!autoPublish) {
            view = new RecordingView(config, Status.DRAFT, List.of(), "Held as a draft because auto_publish was false.", null);
        } else {
            String conflict = conflictWithManualStub(config);
            view = conflict != null
                    ? new RecordingView(config, Status.PENDING_REVIEW, List.of(), conflict, null)
                    : publish(config);
        }
        store.put(config.stubConfigId(), view);
        trim();
        return view;
    }

    public synchronized RecordingView approve(String id) {
        RecordingView v = find(id);
        if (v.status() == Status.PUBLISHED) {
            return v;
        }
        if (v.status() == Status.REJECTED) {
            throw new ConflictException("configuration '" + id + "' was rejected");
        }
        String conflict = conflictWithManualStub(v.config());
        if (conflict != null) {
            throw new ConflictException(conflict);
        }
        RecordingView published = publish(v.config());
        store.put(id, published);
        return published;
    }

    public synchronized RecordingView reject(String id) {
        RecordingView v = find(id);
        if (v.status() == Status.PUBLISHED) {
            throw new ConflictException("configuration '" + id + "' is already published; delete its stubs instead");
        }
        RecordingView rejected = new RecordingView(v.config(), Status.REJECTED, List.of(), "Rejected by a reviewer.",
                clock.instant());
        store.put(id, rejected);
        return rejected;
    }

    public synchronized List<RecordingView> list() {
        List<RecordingView> out = new ArrayList<>(store.values());
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized RecordingView find(String id) {
        RecordingView v = store.get(id);
        if (v == null) {
            throw new NotFoundException("no recording configuration '" + id + "'");
        }
        return v;
    }

    private RecordingView publish(StubConfig config) {
        List<StubDefinition> stubs = config.virtualStubs();
        registry.upsertAll(stubs);
        return new RecordingView(config, Status.PUBLISHED, stubs.stream().map(StubDefinition::stubId).toList(),
                "Published " + stubs.size() + (stubs.size() == 1 ? " stub." : " stubs."), clock.instant());
    }

    private String conflictWithManualStub(StubConfig config) {
        for (StubDefinition s : config.virtualStubs()) {
            var existing = registry.get(s.stubId());
            if (existing.isPresent() && existing.get().definition().source() != StubSource.RECORDED) {
                return "Stub id '" + s.stubId() + "' belongs to a " + existing.get().definition().source().name().toLowerCase()
                        + " stub; recorded stubs never overwrite it.";
            }
        }
        return null;
    }

    private String uniqueId(String base) {
        if (!store.containsKey(base)) {
            return base;
        }
        int n = 2;
        while (store.containsKey(base + "_" + n)) {
            n++;
        }
        return base + "_" + n;
    }

    private void trim() {
        while (store.size() > maxStored) {
            String oldest = store.keySet().iterator().next();
            store.remove(oldest);
        }
    }
}
