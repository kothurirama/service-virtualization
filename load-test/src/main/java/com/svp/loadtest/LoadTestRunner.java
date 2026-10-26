package com.svp.loadtest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Dependency-free HTTP load generator for the running platform.
 *
 * <p>Each connection is a persistent HTTP/1.1 keep-alive socket driven by one virtual thread. A
 * hand-rolled client is used instead of {@code java.net.http.HttpClient} because, with the load
 * generator sharing the machine with the server, every CPU cycle the client burns is one the
 * server does not get; this client does almost nothing but write bytes and parse a status line,
 * headers and a Content-Length body.
 *
 * <p>Two latencies are reported: client round trip, and the server's engine latency read from the
 * {@code X-SVP-Engine-Latency-Ms} response header (the number the assignment's p99 target applies to,
 * measured <em>during</em> load).
 *
 * <pre>
 *   java -jar load-test/target/load-test.jar --url http://localhost:8080 --connections 64 \
 *        --duration 60 --rate 20000 --out target/load-test.json
 * </pre>
 */
public final class LoadTestRunner {

    record Options(URI url, int connections, int durationSeconds, int warmupRequests, int rate, int sessions,
                   int preload, Path out) {
    }

    /** One request of the mix, pre-serialised. */
    record Op(String name, byte[] bytes, int expectedStatus) {
    }

    static final class Stats {
        final double[] client;
        final double[] engine;
        int n;
        long unexpected;
        long ioErrors;
        final Map<Integer, Long> statuses = new TreeMap<>();
        final Map<String, Long> ops = new TreeMap<>();

        Stats(int capacity) {
            client = new double[capacity];
            engine = new double[capacity];
        }

        void add(String op, int status, int expected, double clientMs, double engineMs) {
            if (n < client.length) {
                client[n] = clientMs;
                engine[n] = engineMs;
                n++;
            }
            statuses.merge(status, 1L, Long::sum);
            ops.merge(op, 1L, Long::sum);
            if (status != expected) {
                unexpected++;
            }
        }
    }

    private static final String ENGINE_HEADER = "x-svp-engine-latency-ms";

    public static void main(String[] args) throws Exception {
        Options o = parse(args);
        String host = o.url().getHost();
        int port = o.url().getPort() < 0 ? 80 : o.url().getPort();
        System.out.printf(Locale.ROOT, "target %s  connections=%d  duration=%ds  rate=%s  sessions=%d%n",
                o.url(), o.connections(), o.durationSeconds(), o.rate() == 0 ? "max" : o.rate() + "/s", o.sessions());

        preload(host, port, o);
        System.out.printf(Locale.ROOT, "warm-up: %,d requests%n", o.warmupRequests());
        run(host, port, o, o.warmupRequests(), 0, null);

        System.out.println("measuring...");
        double cpuBefore = processCpuSeconds();
        long start = System.nanoTime();
        List<Stats> perConnection = run(host, port, o, Long.MAX_VALUE, o.durationSeconds(), null);
        double seconds = (System.nanoTime() - start) / 1e9;
        double clientCpu = processCpuSeconds() - cpuBefore;
        report(o, perConnection, seconds, clientCpu);
    }

    /** Creates {@code preload} accounts in every session so reads hit real state. */
    private static void preload(String host, int port, Options o) throws IOException {
        try (Conn c = new Conn(host, port)) {
            for (int s = 0; s < o.sessions(); s++) {
                for (int k = 0; k < o.preload(); k++) {
                    String body = "{\"account_id\":\"ACC-P" + k + "\",\"account_holder\":\"Load " + k
                            + "\",\"initial_deposit\":100.0,\"currency\":\"USD\"}";
                    int status = c.exchange(request("POST", "/virtual/api/v1/accounts", session(s), body)).status;
                    if (status != 201) {
                        throw new IllegalStateException("preload failed with HTTP " + status + " - is the platform running?");
                    }
                }
            }
        }
    }

    private static String session(int s) {
        return "load-" + s;
    }

    /**
     * Request mix (same shape as the in-process benchmark): 50% state reads, 25% creates, 7% merges,
     * 3% deletes of possibly-missing ids, 15% stateless matching (array JSONPath, header regex,
     * query constraint) and misses.
     */
    static Op next(SplittableRandom r, Options o, long seq) {
        String session = session(r.nextInt(o.sessions()));
        int k = r.nextInt(o.preload());
        double p = r.nextDouble();
        if (p < 0.50) {
            return new Op("get_account", request("GET", "/virtual/api/v1/accounts/ACC-P" + k, session, null), 200);
        }
        if (p < 0.75) {
            return new Op("create_account", request("POST", "/virtual/api/v1/accounts", session,
                    "{\"account_holder\":\"Load " + seq + "\",\"initial_deposit\":100.0,\"currency\":\"USD\"}"), 201);
        }
        if (p < 0.82) {
            return new Op("merge_account", request("PUT", "/virtual/api/v1/accounts/ACC-P" + k, session,
                    "{\"last_seq\":" + seq + "}"), 200);
        }
        if (p < 0.85) {
            // ids ACC-1001.. are created concurrently; a miss is a legitimate 404, so only the stub matters here
            return new Op("delete_account", request("DELETE", "/virtual/api/v1/accounts/ACC-" + (1001 + r.nextInt(5000)),
                    session, null), -1);
        }
        if (p < 0.90) {
            return new Op("order_jsonpath", request("POST", "/virtual/api/v1/orders", session,
                    "{\"items\":[{\"sku\":\"SKU-" + k + "\",\"category\":\"BOOKS\"},{\"sku\":\"SKU-PREMIUM\",\"category\":\"X\"}]}"),
                    201);
        }
        if (p < 0.94) {
            return new Op("header_regex", request("GET", "/virtual/api/v1/secure/profile", session, null,
                    "Authorization: Bearer token-" + k), 200);
        }
        if (p < 0.97) {
            return new Op("query_match", request("GET", "/virtual/api/v1/transactions?status=PENDING", session, null), 200);
        }
        return new Op("no_match", request("GET", "/virtual/api/v2/unknown/" + k, session, null), 404);
    }

    static byte[] request(String method, String path, String session, String body, String... extraHeaders) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: svp\r\nX-Session-Id: ").append(session)
                .append("\r\n");
        for (String h : extraHeaders) {
            sb.append(h).append("\r\n");
        }
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            sb.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(head, head.length + payload.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    /**
     * Runs all connections until {@code totalRequests} are done or {@code durationSeconds} elapse.
     * With a target rate, each connection is paced open-loop at rate/connections and latency is
     * measured from the <em>scheduled</em> send time (no coordinated omission).
     */
    private static List<Stats> run(String host, int port, Options o, long totalRequests, int durationSeconds,
                                   Void unused) throws InterruptedException {
        boolean measuring = durationSeconds > 0;
        int capacityPerConn = measuring
                ? (int) Math.min(20_000_000L / o.connections(), (o.rate() > 0 ? (long) o.rate() : 60_000L)
                * durationSeconds / o.connections() * 2 + 1000)
                : 1;
        AtomicLong issued = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        List<Stats> all = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(o.connections());
        long deadline = measuring ? System.nanoTime() + durationSeconds * 1_000_000_000L : Long.MAX_VALUE;
        double intervalNanos = o.rate() > 0 && measuring ? 1e9 * o.connections() / o.rate() : 0;

        for (int c = 0; c < o.connections(); c++) {
            Stats stats = new Stats(capacityPerConn);
            all.add(stats);
            int id = c;
            Thread.ofVirtual().start(() -> {
                SplittableRandom r = new SplittableRandom(31L * id + (measuring ? 7 : 3));
                long scheduled = System.nanoTime() + (long) (intervalNanos * id / o.connections());
                Conn conn = null;
                try {
                    while (!stop.get()) {
                        long seq = issued.getAndIncrement();
                        if (seq >= totalRequests || System.nanoTime() >= deadline) {
                            break;
                        }
                        if (intervalNanos > 0) {
                            long wait = scheduled - System.nanoTime();
                            if (wait > 0) {
                                LockSupport.parkNanos(wait);
                            }
                        }
                        Op op = next(r, o, seq);
                        long t0 = intervalNanos > 0 ? scheduled : System.nanoTime();
                        scheduled += (long) intervalNanos;
                        try {
                            if (conn == null) {
                                conn = new Conn(host, port);
                            }
                            Response res = conn.exchange(op.bytes());
                            double clientMs = (System.nanoTime() - t0) / 1e6;
                            int expected = op.expectedStatus() < 0 ? res.status : op.expectedStatus();
                            if (measuring) {
                                stats.add(op.name(), res.status, expected, clientMs, res.engineMs);
                            }
                        } catch (IOException e) {
                            stats.ioErrors++;
                            if (conn != null) {
                                conn.close();
                            }
                            conn = null;
                        }
                    }
                } finally {
                    if (conn != null) {
                        conn.close();
                    }
                    done.countDown();
                }
            });
        }
        done.await();
        return all;
    }

    record Response(int status, double engineMs) {
    }

    /** Minimal keep-alive HTTP/1.1 connection (Content-Length bodies only, which is all Tomcat sends here). */
    static final class Conn implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;
        private final byte[] line = new byte[8192];

        Conn(String host, int port) throws IOException {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            out = socket.getOutputStream();
            in = new BufferedInputStream(socket.getInputStream(), 16384);
        }

        Response exchange(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            String statusLine = readLine();
            if (statusLine == null || statusLine.length() < 12) {
                throw new IOException("connection closed");
            }
            int status = Integer.parseInt(statusLine.substring(9, 12));
            int contentLength = 0;
            boolean chunked = false;
            double engine = Double.NaN;
            String h;
            while ((h = readLine()) != null && !h.isEmpty()) {
                int colon = h.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = h.substring(colon + 1).trim();
                switch (name) {
                    case "content-length" -> contentLength = Integer.parseInt(value);
                    case "transfer-encoding" -> chunked = value.equalsIgnoreCase("chunked");
                    case ENGINE_HEADER -> engine = Double.parseDouble(value);
                    default -> { }
                }
            }
            if (chunked) {
                readChunked();
            } else {
                skip(contentLength);
            }
            return new Response(status, engine);
        }

        private void readChunked() throws IOException {
            while (true) {
                String size = readLine();
                if (size == null) {
                    throw new IOException("closed in chunked body");
                }
                int semi = size.indexOf(';');
                int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
                skip(n);
                readLine();
                if (n == 0) {
                    return;
                }
            }
        }

        private void skip(int n) throws IOException {
            while (n > 0) {
                long s = in.skip(n);
                if (s <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("closed in body");
                    }
                    s = 1;
                }
                n -= (int) s;
            }
        }

        private String readLine() throws IOException {
            int len = 0;
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    int end = len > 0 && line[len - 1] == '\r' ? len - 1 : len;
                    return new String(line, 0, end, StandardCharsets.ISO_8859_1);
                }
                if (len < line.length) {
                    line[len++] = (byte) b;
                }
            }
            return len == 0 ? null : new String(line, 0, len, StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static void report(Options o, List<Stats> perConnection, double seconds, double clientCpuSeconds)
            throws IOException {
        int total = perConnection.stream().mapToInt(s -> s.n).sum();
        double[] client = new double[total];
        double[] engine = new double[total];
        int pos = 0;
        long unexpected = 0;
        long ioErrors = 0;
        Map<Integer, Long> statuses = new TreeMap<>();
        Map<String, Long> ops = new TreeMap<>();
        for (Stats s : perConnection) {
            System.arraycopy(s.client, 0, client, pos, s.n);
            System.arraycopy(s.engine, 0, engine, pos, s.n);
            pos += s.n;
            unexpected += s.unexpected;
            ioErrors += s.ioErrors;
            s.statuses.forEach((k, v) -> statuses.merge(k, v, Long::sum));
            s.ops.forEach((k, v) -> ops.merge(k, v, Long::sum));
        }
        if (System.getProperty("svp.debug") != null) {
            for (int i = 0; i < perConnection.size(); i++) {
                Stats s = perConnection.get(i);
                double[] c = Arrays.copyOf(s.client, s.n);
                int worst = 0;
                for (int j = 1; j < s.n; j++) {
                    if (s.client[j] > s.client[worst]) {
                        worst = j;
                    }
                }
                int over100 = 0;
                for (int j = 0; j < s.n; j++) {
                    if (s.client[j] > 100) {
                        over100++;
                    }
                }
                System.out.printf(Locale.ROOT, "conn %2d worst at %.1f%% of run, %d reqs >100ms (first at %.1f%%)%n", i,
                        100.0 * worst / s.n, over100, 100.0 * firstOver(s, 100) / s.n);
                Arrays.sort(c);
                System.out.printf(Locale.ROOT, "conn %2d n=%6d p50=%9.2f p99=%9.2f max=%9.2f%n", i, s.n, pct(c, 50), pct(c, 99),
                        c.length == 0 ? 0 : c[c.length - 1]);
            }
        }
        Arrays.sort(client);
        double[] engineKnown = Arrays.stream(engine).filter(d -> !Double.isNaN(d)).sorted().toArray();
        long engineUnder10 = Arrays.stream(engineKnown).filter(d -> d < 10.0).count();
        int cores = Runtime.getRuntime().availableProcessors();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("target_rps", o.rate() == 0 ? "max" : o.rate());
        r.put("achieved_rps", round(total / seconds));
        r.put("requests", total);
        r.put("duration_s", round(seconds));
        r.put("connections", o.connections());
        r.put("sessions", o.sessions());
        r.put("unexpected_status", unexpected);
        r.put("io_errors", ioErrors);
        r.put("client_latency_ms", Map.of("p50", round(pct(client, 50)), "p95", round(pct(client, 95)),
                "p99", round(pct(client, 99)), "max", round(client.length == 0 ? 0 : client[client.length - 1])));
        r.put("engine_latency_ms", Map.of("p50", round(pct(engineKnown, 50)), "p95", round(pct(engineKnown, 95)),
                "p99", round(pct(engineKnown, 99)), "p999", round(pct(engineKnown, 99.9)),
                "max", round(engineKnown.length == 0 ? 0 : engineKnown[engineKnown.length - 1])));
        r.put("engine_sub_10ms_pass_rate", engineKnown.length == 0 ? 0 : (double) engineUnder10 / engineKnown.length);
        r.put("status_counts", statuses);
        r.put("op_counts", ops);
        r.put("load_generator_cpu_share", round(clientCpuSeconds / (seconds * cores)));
        r.put("machine", Map.of("available_processors", cores, "os", System.getProperty("os.name") + " "
                + System.getProperty("os.arch"), "java", System.getProperty("java.version")));

        String json = toJson(r, 0);
        System.out.println(json);
        if (o.out() != null) {
            if (o.out().getParent() != null) {
                Files.createDirectories(o.out().getParent());
            }
            Files.writeString(o.out(), json);
            System.out.println("written to " + o.out());
        }
    }

    private static int firstOver(Stats s, double ms) {
        for (int j = 0; j < s.n; j++) {
            if (s.client[j] > ms) {
                return j;
            }
        }
        return s.n;
    }

    private static double processCpuSeconds() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getProcessCpuTime() / 1e9;
        }
        return 0;
    }

    static double pct(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String toJson(Object v, int indent) {
        String pad = "  ".repeat(indent + 1);
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(pad).append('"').append(e.getKey()).append("\": ").append(toJson(e.getValue(), indent + 1));
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            return sb.append("  ".repeat(indent)).append('}').toString();
        }
        if (v instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return String.valueOf(v);
    }

    static Options parse(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            String key = args[i].substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            a.put(key, value);
        }
        if (a.containsKey("help")) {
            System.out.println("""
                    options: --url http://localhost:8080  --connections 64  --duration 60
                             --rate 0 (0 = as fast as possible)  --warmup 50000  --sessions 64
                             --preload 100 (accounts per session)  --out target/load-test.json""");
            System.exit(0);
        }
        Path out = a.containsKey("out") ? Path.of(a.get("out")) : null;
        return new Options(URI.create(a.getOrDefault("url", "http://localhost:8080")),
                Integer.parseInt(a.getOrDefault("connections", "64")),
                Integer.parseInt(a.getOrDefault("duration", "60")),
                Integer.parseInt(a.getOrDefault("warmup", "50000")),
                Integer.parseInt(a.getOrDefault("rate", "0")),
                Integer.parseInt(a.getOrDefault("sessions", "64")),
                Integer.parseInt(a.getOrDefault("preload", "100")),
                out);
    }
}
