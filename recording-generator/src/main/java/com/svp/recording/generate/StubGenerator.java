package com.svp.recording.generate;

import com.svp.engine.StubValidationException;
import com.svp.engine.json.JsonValues;
import com.svp.engine.match.StubCompiler;
import com.svp.engine.model.RequestMatcherDef;
import com.svp.engine.model.ResponseTemplateDef;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;
import com.svp.recording.RawTrafficLog;
import com.svp.recording.sanitize.Category;
import com.svp.recording.sanitize.Detection;
import com.svp.recording.sanitize.KeyClassifier;
import com.svp.recording.sanitize.SanitizedLog;
import com.svp.recording.sanitize.SensitiveDataSanitizer;
import com.svp.recording.sanitize.SensitiveType;
import com.svp.recording.sanitize.ValueDetectors;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns sanitised recordings into replayable stubs:
 * <ol>
 *   <li>Path: id-like segments (numbers, UUIDs, {@code ACC-9921}, masked values) become variables
 *       named after the collection ({@code /accounts/{accountId}}).</li>
 *   <li>Matcher: method + path + Content-Type, plus a few low-cardinality "discriminator" fields
 *       ({@code merchant_id}, {@code currency}, {@code type}...) — never sensitive or volatile fields.</li>
 *   <li>Response: the sanitised recorded response, with values that echo the request turned into
 *       templates ({@code {{request.path.accountId}}}) so one stub serves every id.</li>
 *   <li>Safety: recorded text cannot inject template syntax; credentials headers are dropped;
 *       every stub is compiled before it is offered for publication.</li>
 * </ol>
 */
public final class StubGenerator {

    public static final String GENERATED_BY = "TrafficRecordingProxy";

    private static final Pattern VERSION_SEGMENT = Pattern.compile("^v\\d+(\\.\\d+)*$");
    private static final Pattern PREFIXED_ID = Pattern.compile("^[A-Za-z]{1,12}[-_]?\\d[A-Za-z0-9_-]*$");
    private static final Set<String> DISCRIMINATOR_KEYS = Set.of("type", "currency", "operation", "action", "channel",
            "category", "method", "kind", "mode", "product", "plan", "country", "region", "tier", "network", "scheme",
            "brand", "intent", "mcc", "status", "eventtype", "paymentmethod", "transactiontype", "accounttype");
    private static final Set<String> VOLATILE_TOKENS = Set.of("request", "correlation", "trace", "idempotency", "nonce",
            "timestamp", "time", "date", "created", "updated", "uuid", "txn", "transaction", "reference", "ref",
            "description", "note", "notes", "memo", "comment", "message", "session", "token", "signature", "version");
    private static final Set<String> PAGING_QUERY = Set.of("page", "size", "limit", "offset", "cursor", "sort", "order",
            "_", "ts", "timestamp", "nocache", "cb", "pagesize", "pagenumber");
    private static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of("set-cookie", "cookie", "authorization", "date",
            "server", "content-length", "transfer-encoding", "connection", "keep-alive", "x-powered-by", "via",
            "x-request-id", "x-correlation-id", "etag", "last-modified", "age", "expires");
    private static final int MAX_BODY_CONSTRAINTS = 3;

    private final SensitiveDataSanitizer sanitizer;
    private final Clock clock;

    public StubGenerator(SensitiveDataSanitizer sanitizer, Clock clock) {
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    private record PathTemplate(String pattern, Map<String, String> variables) {
    }

    private record Candidate(StubDefinition stub, int logIndex, int observations, Integer status) {
    }

    public StubConfig generate(String serviceName, List<RawTrafficLog> logs) {
        if (logs == null || logs.isEmpty()) {
            throw new IllegalArgumentException("at least one traffic log is required");
        }
        String service = serviceName == null || serviceName.isBlank() ? deriveService(logs.get(0).path()) : serviceName;
        String serviceSlug = slug(service);

        List<FieldDetection> detections = new ArrayList<>();
        List<ReviewItem> review = new ArrayList<>();
        Map<String, Candidate> byMatcher = new LinkedHashMap<>();
        int inspected = 0;
        int counter = 0;

        for (int i = 0; i < logs.size(); i++) {
            RawTrafficLog log = logs.get(i);
            SanitizedLog safe = sanitizer.sanitize(log);
            inspected += safe.fieldsInspected();
            for (Detection d : safe.detections()) {
                detections.add(FieldDetection.of(i, d));
            }

            PathTemplate path = templatePath(safe.path());
            Map<String, Object> bodyConstraints = discriminators(safe);
            Map<String, Object> queryConstraints = queryConstraints(safe);
            Map<String, Object> headerConstraints = new LinkedHashMap<>();
            String contentType = header(safe.requestHeaders(), "Content-Type");
            if (safe.requestBody() != null && contentType != null) {
                headerConstraints.put("Content-Type", contentType.split(";")[0].trim());
            }
            String matcherKey = safe.method() + " " + path.pattern() + " " + new TreeMap<>(bodyConstraints)
                    + new TreeMap<>(queryConstraints) + headerConstraints;

            Candidate existing = byMatcher.get(matcherKey);
            if (existing != null) {
                byMatcher.put(matcherKey, new Candidate(existing.stub(), existing.logIndex(), existing.observations() + 1,
                        existing.status()));
                if (!existing.status().equals(safe.responseStatus())) {
                    review.add(new ReviewItem("response.status_code", i, existing.stub().stubId(), null, null,
                            "recordings for the same request matcher returned different statuses ("
                                    + existing.status() + " vs " + safe.responseStatus() + "); the first was kept",
                            "VERIFY"));
                }
                continue;
            }

            String stubId = "rec_" + serviceSlug + "_" + safe.method().toLowerCase(Locale.ROOT) + "_"
                    + routeSlug(path.pattern()) + "_" + String.format("%03d", ++counter);
            Map<String, Object> echoSources = echoSources(safe);
            Object responseBody = templatise(safe.responseBody(), path.variables(), echoSources, null);
            Map<String, String> responseHeaders = responseHeaders(safe, path.variables());

            StubDefinition stub = new StubDefinition(stubId,
                    "Recorded from " + safe.method() + " " + safe.path(),
                    true, 0, List.of("recorded", serviceSlug), StubSource.RECORDED,
                    new RequestMatcherDef("HTTP", safe.method(), path.pattern(),
                            headerConstraints.isEmpty() ? null : headerConstraints,
                            queryConstraints.isEmpty() ? null : queryConstraints,
                            bodyConstraints.isEmpty() ? null : bodyConstraints),
                    new ResponseTemplateDef(safe.responseStatus(), responseHeaders.isEmpty() ? null : responseHeaders,
                            responseBody),
                    null, null);
            try {
                StubCompiler.compile(stub, 0, null);
                byMatcher.put(matcherKey, new Candidate(stub, i, 1, safe.responseStatus()));
            } catch (StubValidationException e) {
                review.add(new ReviewItem("stub", i, stubId, null, null,
                        "generated stub does not compile: " + String.join("; ", e.errors()), "FIX_OR_DISCARD"));
            }
        }

        double lowest = 1.0;
        int pci = 0;
        int pii = 0;
        int secret = 0;
        for (FieldDetection d : detections) {
            switch (Category.valueOf(d.category())) {
                case PCI -> pci++;
                case PII -> pii++;
                case SECRET -> secret++;
            }
            lowest = Math.min(lowest, d.confidence());
            if (d.confidence() < SensitiveDataSanitizer.AUTO_THRESHOLD) {
                SensitiveType type = SensitiveType.valueOf(d.type());
                review.add(new ReviewItem(d.field(), d.logIndex(), null, d.type(), d.confidence(), d.reason(),
                        type.category() == Category.SECRET ? "REDACT" : "VERIFY_MASKING"));
            }
        }

        List<StubDefinition> stubs = byMatcher.values().stream()
                .map(c -> c.observations() > 1
                        ? withDescription(c.stub(), c.stub().description() + " (" + c.observations() + " recordings)")
                        : c.stub())
                .toList();

        SanitizationSummary summary = new SanitizationSummary(inspected, pci, pii, secret, !review.isEmpty(), logs.size(),
                stubs.size(), detections.isEmpty() ? null : lowest);
        String configId = "AUTO_GEN_" + service.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                + "_" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return new StubConfig(configId, GENERATED_BY, clock.instant(), service, summary, stubs, review, detections);
    }

    private static StubDefinition withDescription(StubDefinition s, String description) {
        return new StubDefinition(s.stubId(), description, s.enabled(), s.priority(), s.tags(), s.source(),
                s.requestMatcher(), s.responseTemplate(), s.stateAction(), s.notFoundResponse());
    }

    // ---------------------------------------------------------------- path

    private static PathTemplate templatePath(String path) {
        String[] raw = path.split("/");
        StringBuilder out = new StringBuilder();
        Map<String, String> vars = new LinkedHashMap<>();
        String previousLiteral = null;
        for (String seg : raw) {
            if (seg.isEmpty()) {
                continue;
            }
            out.append('/');
            if (isIdSegment(java.net.URLDecoder.decode(seg, java.nio.charset.StandardCharsets.UTF_8))) {
                String name = uniqueName(variableName(previousLiteral), vars.keySet());
                vars.put(name, seg);
                out.append('{').append(name).append('}');
            } else {
                out.append(seg);
                previousLiteral = seg;
            }
        }
        return new PathTemplate(out.length() == 0 ? "/" : out.toString(), vars);
    }

    static boolean isIdSegment(String seg) {
        if (VERSION_SEGMENT.matcher(seg).matches()) {
            return false;
        }
        if (ValueDetectors.digitsOnly(seg) != null || ValueDetectors.isUuid(seg) || seg.indexOf('@') > 0) {
            return true;
        }
        if (seg.length() >= 16 && ValueDetectors.isHex(seg)) {
            return true;
        }
        return PREFIXED_ID.matcher(seg).matches() && ValueDetectors.countDigits(seg) >= 2;
    }

    static String variableName(String previousLiteral) {
        if (previousLiteral == null) {
            return "id";
        }
        List<String> tokens = KeyClassifier.tokens(previousLiteral);
        if (tokens.isEmpty()) {
            return "id";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String t = i == tokens.size() - 1 ? singular(tokens.get(i)) : tokens.get(i);
            sb.append(i == 0 ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1));
        }
        return sb.append("Id").toString();
    }

    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 3) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("sses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String uniqueName(String base, Set<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        int n = 2;
        while (used.contains(base + n)) {
            n++;
        }
        return base + n;
    }

    private static String routeSlug(String pattern) {
        List<String> literals = new ArrayList<>();
        for (String seg : pattern.split("/")) {
            if (!seg.isEmpty() && !seg.startsWith("{") && !seg.equals("api") && !VERSION_SEGMENT.matcher(seg).matches()) {
                literals.add(seg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"));
            }
        }
        if (literals.isEmpty()) {
            return "root";
        }
        return String.join("_", literals.subList(Math.max(0, literals.size() - 3), literals.size()));
    }

    // ---------------------------------------------------------------- matcher selection

    private static Map<String, Object> discriminators(SanitizedLog safe) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(safe.requestBody() instanceof Map<?, ?> body)) {
            return out;
        }
        for (Map.Entry<?, ?> e : body.entrySet()) {
            if (out.size() >= MAX_BODY_CONSTRAINTS) {
                break;
            }
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (!(value instanceof String) && !(value instanceof Boolean)) {
                continue;
            }
            if (value instanceof String s && (s.isEmpty() || s.length() > 64 || ValueDetectors.isUuid(s)
                    || s.chars().anyMatch(Character::isWhitespace))) {
                continue;
            }
            if (safe.isSensitive("request.body." + key) || !key.matches("[A-Za-z0-9_\\-]+")) {
                continue;
            }
            List<String> tokens = KeyClassifier.tokens(key);
            if (tokens.isEmpty() || tokens.stream().anyMatch(VOLATILE_TOKENS::contains)) {
                continue;
            }
            String joined = String.join("", tokens);
            boolean isId = tokens.get(tokens.size() - 1).equals("id") && tokens.size() > 1;
            if (isId || DISCRIMINATOR_KEYS.contains(joined)) {
                out.put("$." + key, value);
            }
        }
        return out;
    }

    private static Map<String, Object> queryConstraints(SanitizedLog safe) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : safe.query().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value == null || value.length() > 64 || safe.isSensitive("request.query." + key)
                    || PAGING_QUERY.contains(key.toLowerCase(Locale.ROOT))
                    || KeyClassifier.tokens(key).stream().anyMatch(VOLATILE_TOKENS::contains)) {
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    // ---------------------------------------------------------------- response templating

    /** Top-level, non-sensitive request body values a response may echo. */
    private static Map<String, Object> echoSources(SanitizedLog safe) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (safe.requestBody() instanceof Map<?, ?> body) {
            for (Map.Entry<?, ?> e : body.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (safe.isSensitive("request.body." + key) || !key.matches("[A-Za-z0-9_\\-]+")) {
                    continue;
                }
                if ((v instanceof String s && s.length() >= 3) || (v instanceof Number n && n.doubleValue() != 0
                        && n.doubleValue() != 1)) {
                    out.put(key, v);
                }
            }
        }
        return out;
    }

    private static Object templatise(Object value, Map<String, String> pathVars, Map<String, Object> echo, String key) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = neutralise(String.valueOf(e.getKey()));
                out.put(k, templatise(e.getValue(), pathVars, echo, k));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(templatise(item, pathVars, echo, key));
            }
            return out;
        }
        if (value instanceof String s) {
            for (Map.Entry<String, String> v : pathVars.entrySet()) {
                if (s.equals(v.getValue())) {
                    return "{{request.path." + v.getKey() + "}}";
                }
            }
            for (Map.Entry<String, Object> e : echo.entrySet()) {
                if (e.getValue() instanceof String src && s.equals(src)
                        && (e.getKey().equals(key) || src.length() >= 6)) {
                    return "{{request.body." + e.getKey() + "}}";
                }
            }
            String text = neutralise(s);
            for (Map.Entry<String, String> v : pathVars.entrySet()) {
                if (v.getValue().length() >= 4 && text.contains(v.getValue())) {
                    text = text.replace(v.getValue(), "{{request.path." + v.getKey() + "}}");
                }
            }
            return text;
        }
        if (value instanceof Number n && key != null) {
            Object src = echo.get(key);
            if (src instanceof Number sn && JsonValues.numericEquals(sn, n)) {
                return "{{request.body." + key + "}}";
            }
        }
        return value;
    }

    /** Recorded text must never be interpreted as a template: "{{" becomes "{ {". */
    static String neutralise(String s) {
        return s.contains("{{") ? s.replace("{{", "{ {") : s;
    }

    private static Map<String, String> responseHeaders(SanitizedLog safe, Map<String, String> pathVars) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : safe.responseHeaders().entrySet()) {
            String name = e.getKey();
            if (name == null || e.getValue() == null || DROPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || safe.isSensitive("response.headers." + name)) {
                continue;
            }
            out.put(name, String.valueOf(templatise(e.getValue(), pathVars, Map.of(), name)));
        }
        return out;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    static String deriveService(String path) {
        if (path == null) {
            return "service";
        }
        Set<String> literals = new LinkedHashSet<>();
        for (String seg : path.split("[/?]")) {
            if (!seg.isEmpty() && !seg.equals("api") && !VERSION_SEGMENT.matcher(seg).matches() && !isIdSegment(seg)) {
                literals.add(seg);
            }
        }
        return literals.isEmpty() ? "service" : literals.iterator().next();
    }

    static String slug(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return out.isEmpty() ? "service" : out;
    }
}
