package com.example.hyperion.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny, dependency-free JSON helper.
 *
 * <p>Hyperion prides itself on having zero third-party dependencies, so the UI does not
 * pull in Jackson/Gson either. This class does exactly two things and nothing more:
 * <ul>
 *   <li>build JSON text with correct escaping ({@link #obj()}, {@link #arr()}, ...),</li>
 *   <li>parse a <i>flat</i> JSON object of scalar values (enough for our request bodies).</li>
 * </ul>
 * It intentionally does not support nested parsing — the UI never sends nested input.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    /** Fluent builder for a JSON object. */
    public static final class Writer {
        private final StringBuilder sb = new StringBuilder(256);
        private boolean first = true;

        private void separator() {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }
        }

        public Writer begin() {
            sb.append('{');
            return this;
        }

        public Writer end() {
            sb.append('}');
            return this;
        }

        public Writer field(String name, String value) {
            separator();
            escape(sb, name);
            sb.append(':');
            if (value == null) {
                sb.append("null");
            } else {
                escape(sb, value);
            }
            return this;
        }

        public Writer field(String name, long value) {
            separator();
            escape(sb, name);
            sb.append(':').append(value);
            return this;
        }

        public Writer field(String name, double value) {
            separator();
            escape(sb, name);
            sb.append(':');
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            return this;
        }

        public Writer field(String name, boolean value) {
            separator();
            escape(sb, name);
            sb.append(':').append(value);
            return this;
        }

        /** Raw field for embedding a pre-rendered JSON fragment (array/object). */
        public Writer rawField(String name, String json) {
            separator();
            escape(sb, name);
            sb.append(':').append(json);
            return this;
        }

        public String done() {
            return sb.toString();
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    public static Writer obj() {
        return new Writer().begin();
    }

    /** Renders an array of strings. */
    public static String strArray(Iterable<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            escape(sb, v);
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ reading

    /**
     * Parses a flat JSON object into a map of string values. Numbers and booleans are
     * returned as their literal text; {@code null} becomes Java {@code null}.
     *
     * @throws IllegalArgumentException on malformed input
     */
    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        int i = skipWs(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') {
            throw new IllegalArgumentException("expected '{'");
        }
        i++;
        while (true) {
            i = skipWs(json, i);
            if (i >= json.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = json.charAt(i);
            if (c == '}') {
                return result;
            }
            if (c == ',') {
                i++;
                continue;
            }
            if (c != '"') {
                throw new IllegalArgumentException("expected '\"' at " + i);
            }
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) != ':') {
                throw new IllegalArgumentException("expected ':' at " + i);
            }
            i = skipWs(json, i + 1);
            if (i >= json.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            String value;
            char vc = json.charAt(i);
            if (vc == '"') {
                StringBuilder val = new StringBuilder();
                i = readString(json, i, val);
                value = val.toString();
            } else if (vc == 'n' && json.startsWith("null", i)) {
                value = null;
                i += 4;
            } else if (vc == 't' && json.startsWith("true", i)) {
                value = "true";
                i += 4;
            } else if (vc == 'f' && json.startsWith("false", i)) {
                value = "false";
                i += 5;
            } else {
                int start = i;
                while (i < json.length() && "-+.eE0123456789".indexOf(json.charAt(i)) >= 0) {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("invalid value at " + i);
                }
                value = json.substring(start, i);
            }
            result.put(key.toString(), value);
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int readString(String s, int i, StringBuilder out) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return i;
            }
            if (c == '\\') {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> out.append(e);
                }
            } else {
                out.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
