package com.svp.engine.match;

import com.svp.engine.model.NearMiss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all enabled stubs: protocol -> method -> {@link PathTrie}. A new snapshot is
 * built on every stub change and swapped in atomically (copy-on-write), so the request path never
 * takes a lock and never sees a half-updated index.
 */
public final class StubIndex {

    public static final StubIndex EMPTY = new StubIndex(Map.of(), 0);
    static final String ANY_METHOD = "*";

    private final Map<String, Map<String, PathTrie>> tries;
    private final int size;

    private StubIndex(Map<String, Map<String, PathTrie>> tries, int size) {
        this.tries = tries;
        this.size = size;
    }

    public static StubIndex build(List<CompiledStub> stubs) {
        Map<String, Map<String, List<CompiledStub>>> grouped = new HashMap<>();
        for (CompiledStub s : stubs) {
            grouped.computeIfAbsent(s.protocol(), p -> new HashMap<>())
                    .computeIfAbsent(s.method(), m -> new ArrayList<>())
                    .add(s);
        }
        Map<String, Map<String, PathTrie>> tries = new HashMap<>();
        grouped.forEach((protocol, byMethod) -> {
            Map<String, PathTrie> m = new HashMap<>();
            byMethod.forEach((method, list) -> m.put(method, PathTrie.build(list)));
            tries.put(protocol, Map.copyOf(m));
        });
        return new StubIndex(Map.copyOf(tries), stubs.size());
    }

    /** Hot path. Method-specific stubs win over method-agnostic ("*") stubs. */
    public CompiledStub find(RequestView request) {
        Map<String, PathTrie> byMethod = tries.get(request.protocol());
        if (byMethod == null) {
            return null;
        }
        PathTrie.Visitor visitor = stub -> stub.matches(request);
        PathTrie exact = byMethod.get(request.method());
        if (exact != null) {
            CompiledStub found = exact.find(request.segments(), visitor);
            if (found != null) {
                return found;
            }
        }
        PathTrie any = byMethod.get(ANY_METHOD);
        return any == null ? null : any.find(request.segments(), visitor);
    }

    /** Miss path only: stubs whose route matched but whose constraints failed, most specific first. */
    public List<NearMiss> nearMisses(RequestView request, int limit) {
        List<NearMiss> out = new ArrayList<>(limit);
        Map<String, PathTrie> byMethod = tries.get(request.protocol());
        if (byMethod == null) {
            return out;
        }
        PathTrie.Visitor collector = stub -> {
            String[] failure = stub.firstFailure(request);
            if (failure != null && out.size() < limit) {
                out.add(new NearMiss(stub.id(), failure[0], failure[1]));
            }
            return false; // keep walking
        };
        for (String method : new String[]{request.method(), ANY_METHOD}) {
            PathTrie t = byMethod.get(method);
            if (t != null) {
                t.find(request.segments(), collector);
            }
        }
        return out;
    }

    public int size() {
        return size;
    }
}
