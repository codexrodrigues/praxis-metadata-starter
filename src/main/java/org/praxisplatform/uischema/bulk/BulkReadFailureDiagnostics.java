package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Private code-only observation at a reader's existing failure-sanitization boundary. */
final class BulkReadFailureDiagnostics {
    enum Reader { PROPOSAL, PROPOSAL_RESULTS }
    enum Stage { SNAPSHOT_TRANSACTION, GLOBAL_AUTHORIZATION, PROTECTED_AUTHORIZATION,
        PROJECTION, PREVIEW, CURSOR_DECODE, AFTER_TRANSACTION }
    enum Reason { EXCEPTION, DEADLINE_EXPIRED, GLOBAL_DECISION_MISSING, GLOBAL_UNAVAILABLE,
        ELIGIBILITY_UNTYPED, PROJECTION_BINDING_CHANGED, REDACTED_INTENT_INVALID,
        PREVIEW_ABSENT, PREVIEW_NOT_EVALUATED }

    static final String PREFIX = "BULK_READ_FAILURE ";
    static final int MAX_THROWABLES = 12, MAX_FRAMES = 32, MAX_EDGES = 32, MAX_SUPPRESSED = 8;
    private static final class Json {
        private static final ObjectMapper MAPPER = new ObjectMapper();
    }
    private BulkReadFailureDiagnostics() { }

    /** At most one inner branch plus one later transaction/deadline failure; no inspection or I/O. */
    static final class Capture {
        private final Reader reader;
        private Stage firstStage, lastStage;
        private Reason firstReason, lastReason;
        private Throwable firstFailure, lastFailure;
        Capture(Reader reader) { this.reader = reader; }
        void record(Stage stage, Reason reason, Throwable failure) {
            if (firstReason == null) {
                firstStage = stage; firstReason = reason; firstFailure = failure;
            } else {
                lastStage = stage; lastReason = reason; lastFailure = failure;
            }
        }
        /** Called after the observation/deadline and owned TX have settled. Each sink is isolated. */
        void emit() {
            if (firstReason != null) BulkReadFailureDiagnostics.emit(reader, firstStage, firstReason, firstFailure);
            if (lastReason != null) BulkReadFailureDiagnostics.emit(reader, lastStage, lastReason, lastFailure);
        }
    }

    static void emit(Reader reader, Stage stage, Reason reason, Throwable failure) {
        // Logger lookup and serialization are inside the isolated observer, too.
        emit(reader, stage, reason, failure,
                event -> LoggerFactory.getLogger(BulkReadFailureDiagnostics.class).error(PREFIX + "{}", event));
    }

    /** Package-private sink seam; no mutable global listener or exported diagnostics API. */
    static void emit(Reader reader, Stage stage, Reason reason, Throwable failure, Consumer<String> sink) {
        try { sink.accept(Json.MAPPER.writeValueAsString(project(reader, stage, reason, failure))); }
        catch (Throwable ignoredDiagnosticFailure) { /* Original reader result always wins. */ }
    }

    static ObjectNode project(Reader reader, Stage stage, Reason reason, Throwable failure) {
        ObjectNode event = Json.MAPPER.createObjectNode().put("schemaVersion", 1)
                .put("reader", reader.name()).put("stage", stage.name()).put("reason", reason.name())
                .put("timestamp", Instant.now().toString()).put("threadId", Thread.currentThread().threadId())
                .put("throwablePresent", failure != null);
        Graph graph = new Graph();
        if (failure != null) { graph.pending.add(failure); graph.identities.put(failure, 0); }
        var nodes = event.putArray("nodes");
        for (int index = 0; index < graph.pending.size(); index++) {
            Throwable current = graph.pending.get(index);
            var node = nodes.addObject().put("index", index)
                    .put("exceptionClass", className(current.getClass().getName()));
            String sqlState = current instanceof SQLException sql ? sql.getSQLState() : null;
            if (sqlState != null && sqlState.matches("[0-9A-Z]{5}")) node.put("sqlState", sqlState);
            else node.putNull("sqlState");
            var frames = node.putArray("frames");
            StackTraceElement[] trace = current.getStackTrace();
            boolean framesTruncated = trace.length > MAX_FRAMES;
            for (int i = 0; i < Math.min(MAX_FRAMES, trace.length); i++) {
                StackTraceElement frame = trace[i];
                String type = frame.getClassName();
                String method = frame.getMethodName();
                if (!canonicalClass(type) || !validMethod(method) || frame.getLineNumber() < -1) {
                    framesTruncated = true; continue;
                }
                frames.addObject().put("className", type).put("methodName", method)
                        .put("lineNumber", frame.getLineNumber());
            }
            node.put("framesTruncated", framesTruncated);
            Throwable cause = current.getCause();
            Integer causeIndex = graph.link(cause);
            if (causeIndex == null) node.putNull("cause"); else node.put("cause", causeIndex);
            node.put("causeTruncated", cause != null && causeIndex == null);
            Throwable[] suppressed = current.getSuppressed();
            boolean suppressedTruncated = suppressed.length > MAX_SUPPRESSED;
            var indices = node.putArray("suppressed");
            for (int i = 0; i < Math.min(MAX_SUPPRESSED, suppressed.length); i++) {
                Integer target = graph.link(suppressed[i]);
                if (target == null) suppressedTruncated = true; else indices.add(target);
            }
            node.put("suppressedTruncated", suppressedTruncated);
        }
        return event.put("throwablesTruncated", graph.throwablesTruncated)
                .put("edgesTruncated", graph.edgesTruncated).put("referencesReused", graph.referencesReused);
    }

    private static final class Graph {
        final IdentityHashMap<Throwable, Integer> identities = new IdentityHashMap<>();
        final ArrayList<Throwable> pending = new ArrayList<>();
        int edges;
        boolean throwablesTruncated, edgesTruncated, referencesReused;
        Integer link(Throwable target) {
            if (target == null) return null;
            if (edges == MAX_EDGES) { edgesTruncated = true; return null; }
            edges++;
            Integer existing = identities.get(target);
            if (existing != null) { referencesReused = true; return existing; }
            if (pending.size() == MAX_THROWABLES) { throwablesTruncated = true; return null; }
            int next = pending.size(); identities.put(target, next); pending.add(target); return next;
        }
    }

    private static String className(String value) { return canonicalClass(value) ? value : "OTHER"; }
    private static boolean canonicalClass(String value) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,299}")) return false;
        return value.startsWith("org.praxisplatform.uischema.bulk.")
                || value.startsWith("org.springframework.jdbc.")
                || value.startsWith("org.springframework.transaction.")
                || value.startsWith("org.springframework.orm.jpa.")
                || value.startsWith("org.postgresql.") || value.startsWith("org.hibernate.")
                || value.startsWith("com.zaxxer.hikari.") || value.startsWith("java.sql.")
                || value.startsWith("java.lang.") || value.startsWith("java.util.concurrent.");
    }
    private static boolean validMethod(String value) {
        return value != null && value.matches("(?:[A-Za-z_$][A-Za-z0-9_$]{0,199}|<init>|<clinit>)");
    }
}
