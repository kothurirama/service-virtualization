package com.svp.platform.web;

import com.svp.engine.StubRegistry;
import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.model.DriftProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Performance drift: global latency/jitter/fault profile plus per-route overrides. */
@RestController
@RequestMapping("/api/v1/drift")
public class DriftController {

    public record DriftView(DriftProfile global, Map<String, DriftProfile> routes, List<String> knownRoutes) {
    }

    /** A route override: {"route":"POST /api/v1/payments","latency_ms":200,"error_rate":0.05,...}. */
    public record RouteDrift(String route, Boolean enabled, Long latencyMs, Long jitterMs,
                             DriftProfile.Distribution distribution, Double errorRate, Integer errorStatus) {
        DriftProfile profile() {
            return new DriftProfile(enabled, latencyMs, jitterMs, distribution, errorRate, errorStatus);
        }
    }

    private final DriftPolicy drift;
    private final StubRegistry registry;

    public DriftController(DriftPolicy drift, StubRegistry registry) {
        this.drift = drift;
        this.registry = registry;
    }

    @GetMapping
    public DriftView get() {
        return new DriftView(drift.global(), drift.routes(), registry.routeKeys());
    }

    @PutMapping("/global")
    public DriftView setGlobal(@RequestBody DriftProfile profile) {
        drift.setGlobal(profile);
        return get();
    }

    @PutMapping("/routes")
    public DriftView setRoute(@RequestBody RouteDrift route) {
        drift.setRoute(route.route(), route.profile());
        return get();
    }

    @DeleteMapping("/routes")
    public ResponseEntity<DriftView> removeRoute(@RequestParam("route") String route) {
        if (!drift.removeRoute(route)) {
            throw new NotFoundException("no drift override for route '" + route + "'");
        }
        return ResponseEntity.ok(get());
    }

    @PostMapping("/reset")
    public DriftView reset() {
        drift.reset();
        return get();
    }
}
