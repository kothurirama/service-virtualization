package com.svp.engine.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Segment trie over stub path patterns. Lookup cost is O(path depth), independent of the number
 * of stubs. Children are tried most-specific first (literal, variable, wildcard, tail), and inside a
 * node stubs are ordered by priority, then constraint count, then registration order — so the
 * first stub whose constraints pass is by definition the most specific match.
 *
 * <p>Instances are immutable after {@link #build}; they are published through a volatile field and
 * read without locks.
 */
final class PathTrie {

    @FunctionalInterface
    interface Visitor {
        /** @return true to accept this stub (stop searching) */
        boolean accept(CompiledStub stub);
    }

    static final Comparator<CompiledStub> PRECEDENCE = Comparator
            .comparingInt(CompiledStub::priority).reversed()
            .thenComparing(Comparator.comparingInt(CompiledStub::constraintCount).reversed())
            .thenComparingLong(CompiledStub::sequence);

    private static final CompiledStub[] NONE = new CompiledStub[0];

    private static final class Node {
        Map<String, Node> literals = Map.of();
        Node variable;
        Node wildcard;
        CompiledStub[] exact = NONE;
        CompiledStub[] tail = NONE;

        // build-time only
        List<CompiledStub> exactBuild;
        List<CompiledStub> tailBuild;
        Map<String, Node> literalBuild;
    }

    private final Node root;

    private PathTrie(Node root) {
        this.root = root;
    }

    static PathTrie build(List<CompiledStub> stubs) {
        Node root = new Node();
        for (CompiledStub stub : stubs) {
            Node node = root;
            boolean tail = false;
            for (PathPattern.Segment seg : stub.pathPattern().segments()) {
                switch (seg.kind()) {
                    case LITERAL -> {
                        if (node.literalBuild == null) {
                            node.literalBuild = new HashMap<>();
                        }
                        node = node.literalBuild.computeIfAbsent(seg.value(), k -> new Node());
                    }
                    case VARIABLE -> {
                        if (node.variable == null) {
                            node.variable = new Node();
                        }
                        node = node.variable;
                    }
                    case WILDCARD -> {
                        if (node.wildcard == null) {
                            node.wildcard = new Node();
                        }
                        node = node.wildcard;
                    }
                    case TAIL -> tail = true;
                }
            }
            if (tail) {
                if (node.tailBuild == null) {
                    node.tailBuild = new ArrayList<>();
                }
                node.tailBuild.add(stub);
            } else {
                if (node.exactBuild == null) {
                    node.exactBuild = new ArrayList<>();
                }
                node.exactBuild.add(stub);
            }
        }
        freeze(root);
        return new PathTrie(root);
    }

    private static void freeze(Node node) {
        if (node == null) {
            return;
        }
        if (node.exactBuild != null) {
            node.exactBuild.sort(PRECEDENCE);
            node.exact = node.exactBuild.toArray(NONE);
            node.exactBuild = null;
        }
        if (node.tailBuild != null) {
            node.tailBuild.sort(PRECEDENCE);
            node.tail = node.tailBuild.toArray(NONE);
            node.tailBuild = null;
        }
        if (node.literalBuild != null) {
            node.literals = new HashMap<>(node.literalBuild);
            node.literalBuild = null;
            node.literals.values().forEach(PathTrie::freeze);
        }
        freeze(node.variable);
        freeze(node.wildcard);
    }

    /** Depth-first, most specific branch first; returns the first stub the visitor accepts. */
    CompiledStub find(String[] segments, Visitor visitor) {
        return dfs(root, segments, 0, visitor);
    }

    private CompiledStub dfs(Node node, String[] segments, int i, Visitor visitor) {
        if (i == segments.length) {
            for (CompiledStub s : node.exact) {
                if (visitor.accept(s)) {
                    return s;
                }
            }
        } else {
            Node literal = node.literals.get(segments[i]);
            if (literal != null) {
                CompiledStub found = dfs(literal, segments, i + 1, visitor);
                if (found != null) {
                    return found;
                }
            }
            if (node.variable != null) {
                CompiledStub found = dfs(node.variable, segments, i + 1, visitor);
                if (found != null) {
                    return found;
                }
            }
            if (node.wildcard != null) {
                CompiledStub found = dfs(node.wildcard, segments, i + 1, visitor);
                if (found != null) {
                    return found;
                }
            }
        }
        for (CompiledStub s : node.tail) { // '**' matches zero or more remaining segments
            if (visitor.accept(s)) {
                return s;
            }
        }
        return null;
    }
}
