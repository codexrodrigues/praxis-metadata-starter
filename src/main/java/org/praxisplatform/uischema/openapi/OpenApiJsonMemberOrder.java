package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Orders object members of JSON already emitted by the producer, preserving each scalar token. */
final class OpenApiJsonMemberOrder {
    private static final JsonFactory KEY_DECODER = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .build();
    private final byte[] json;
    private int cursor;

    private OpenApiJsonMemberOrder(byte[] json) {
        this.json = json;
    }

    static byte[] sort(byte[] json) throws IOException {
        var input = new OpenApiJsonMemberOrder(json);
        var root = input.value();
        input.whitespace();
        if (input.cursor != json.length) throw invalid();
        if (root == null) return json;
        var output = new ByteArrayOutputStream(json.length);
        output.write(json, 0, root.start());
        root.write(json, output);
        output.write(json, root.end(), json.length - root.end());
        return output.toByteArray();
    }

    private Rewrite value() throws IOException {
        whitespace();
        return switch (peek()) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> {
                string();
                yield null;
            }
            default -> {
                int start = cursor;
                // The producer has serialized the scalar already. Its spelling is part of the value.
                while (cursor < json.length && !isWhitespace(peek()) && peek() != ','
                        && peek() != '}' && peek() != ']') cursor++;
                if (cursor == start) throw invalid();
                yield null;
            }
        };
    }

    private Rewrite object() throws IOException {
        int start = cursor++;
        whitespace();
        if (take('}')) return null;
        List<Member> members = new ArrayList<>();
        boolean ordered = true;
        String previous = null;
        do {
            whitespace();
            int memberStart = cursor;
            String key = key();
            if (previous != null && previous.compareTo(key) >= 0) ordered = false;
            previous = key;
            whitespace();
            if (!take(':')) throw invalid();
            var nested = value();
            members.add(new Member(key, memberStart, cursor, nested));
            whitespace();
        } while (take(','));
        if (!take('}')) throw invalid();
        int end = cursor;
        if (ordered) {
            var nested = members.stream().map(Member::value).filter(java.util.Objects::nonNull).toList();
            return nested.isEmpty() ? null : new Nested(start, end, nested);
        }
        var sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(Member::key));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).key().equals(sorted.get(i).key())) throw invalid();
        }
        return new ObjectMembers(start, end, members, sorted);
    }

    private Rewrite array() throws IOException {
        int start = cursor++;
        whitespace();
        if (take(']')) return null;
        List<Rewrite> nested = new ArrayList<>();
        do {
            var next = value();
            if (next != null) nested.add(next);
            whitespace();
        } while (take(','));
        if (!take(']')) throw invalid();
        return nested.isEmpty() ? null : new Nested(start, cursor, nested);
    }

    private String key() throws IOException {
        int start = cursor;
        if (!string()) return new String(json, start + 1, cursor - start - 2, StandardCharsets.UTF_8);
        try (var parser = KEY_DECODER.createParser(json, start, cursor - start)) {
            parser.nextToken();
            return parser.getText();
        }
    }

    private boolean string() throws IOException {
        if (!take('"')) throw invalid();
        boolean escaped = false;
        while (cursor < json.length) {
            byte next = json[cursor++];
            if (next == '"') return escaped;
            if (next == '\\') {
                escaped = true;
                cursor++;
            }
        }
        throw invalid();
    }

    private boolean take(int expected) {
        if (peek() != expected) return false;
        cursor++;
        return true;
    }

    private int peek() {
        return cursor < json.length ? json[cursor] : -1;
    }

    private void whitespace() {
        while (isWhitespace(peek())) cursor++;
    }

    private static boolean isWhitespace(int token) {
        return token == ' ' || token == '\n' || token == '\r' || token == '\t';
    }

    private static IOException invalid() {
        return new IOException("Invalid or duplicate OpenAPI JSON object members");
    }

    private interface Rewrite {
        int start();
        int end();
        void write(byte[] json, ByteArrayOutputStream output);
    }

    private record Member(String key, int start, int end, Rewrite value) {
        void write(byte[] json, ByteArrayOutputStream output) {
            if (value == null) {
                output.write(json, start, end - start);
            } else {
                output.write(json, start, value.start() - start);
                value.write(json, output);
            }
        }
    }

    private record ObjectMembers(int start, int end, List<Member> original, List<Member> sorted) implements Rewrite {
        @Override
        public void write(byte[] json, ByteArrayOutputStream output) {
            int position = start;
            for (int i = 0; i < original.size(); i++) {
                var slot = original.get(i);
                output.write(json, position, slot.start() - position);
                sorted.get(i).write(json, output);
                position = slot.end();
            }
            output.write(json, position, end - position);
        }
    }

    private record Nested(int start, int end, List<Rewrite> children) implements Rewrite {
        @Override
        public void write(byte[] json, ByteArrayOutputStream output) {
            int position = start;
            for (var child : children) {
                output.write(json, position, child.start() - position);
                child.write(json, output);
                position = child.end();
            }
            output.write(json, position, end - position);
        }
    }
}
