package com.svp.engine.match;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parsed stub path pattern.
 * <pre>
 *   /api/v1/accounts            literal
 *   /api/v1/accounts/{id}       path variable (one segment, captured as request.path.id)
 *   /api/v1/fx/*&#47;rate          single-segment wildcard
 *   /api/v1/legacy/**           tail wildcard: zero or more remaining segments (last only)
 * </pre>
 */
public final class PathPattern {

    public enum Kind { LITERAL, VARIABLE, WILDCARD, TAIL }

    public record Segment(Kind kind, String value) {
    }

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String source;
    private final List<Segment> segments;
    private final int[] variablePositions;
    private final String[] variableNames;
    private final int tailPosition;

    private PathPattern(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
        List<Integer> positions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int tail = -1;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.kind() == Kind.VARIABLE) {
                positions.add(i);
                names.add(s.value());
            } else if (s.kind() == Kind.TAIL) {
                tail = i;
            }
        }
        this.variablePositions = positions.stream().mapToInt(Integer::intValue).toArray();
        this.variableNames = names.toArray(new String[0]);
        this.tailPosition = tail;
    }

    public static PathPattern parse(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (pattern.indexOf('?') >= 0) {
            throw new IllegalArgumentException("path must not contain a query string; use request_matcher.query");
        }
        String[] raw = RequestView.split(pattern.trim());
        List<Segment> segments = new ArrayList<>(raw.length);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i];
            if (s.equals("**")) {
                if (i != raw.length - 1) {
                    throw new IllegalArgumentException("'**' is only allowed as the last path segment");
                }
                segments.add(new Segment(Kind.TAIL, "**"));
            } else if (s.equals("*")) {
                segments.add(new Segment(Kind.WILDCARD, "*"));
            } else if (s.startsWith("{") && s.endsWith("}")) {
                String name = s.substring(1, s.length() - 1).trim();
                if (!VARIABLE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid path variable name '" + name + "'");
                }
                if (seen.contains(name)) {
                    throw new IllegalArgumentException("duplicate path variable '" + name + "'");
                }
                seen.add(name);
                segments.add(new Segment(Kind.VARIABLE, name));
            } else if (s.indexOf('{') >= 0 || s.indexOf('}') >= 0 || s.indexOf('*') >= 0) {
                throw new IllegalArgumentException("segment '" + s
                        + "' mixes literal text with a variable or wildcard; use a whole segment");
            } else {
                segments.add(new Segment(Kind.LITERAL, s));
            }
        }
        return new PathPattern(pattern.trim(), segments);
    }

    public String source() {
        return source;
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean isLiteral() {
        return segments.stream().allMatch(s -> s.kind() == Kind.LITERAL);
    }

    public int[] variablePositions() {
        return variablePositions;
    }

    public String[] variableNames() {
        return variableNames;
    }

    /** @return index of the {@code **} segment, or -1 */
    public int tailPosition() {
        return tailPosition;
    }

    public boolean hasVariable(String name) {
        for (String n : variableNames) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Canonical form used for route keys ("/a/{id}" and "/a/{id}/" are the same route). */
    public String canonical() {
        return RequestView.normalisedPath(segments.stream()
                .map(s -> s.kind() == Kind.VARIABLE ? "{" + s.value() + "}" : s.value())
                .toArray(String[]::new));
    }
}
