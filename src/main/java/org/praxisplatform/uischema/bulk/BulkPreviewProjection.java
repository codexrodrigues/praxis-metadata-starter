package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/** Provider-approved public evaluation projection. It is not an authorization decision. */
@JsonIgnoreType
public final class BulkPreviewProjection {
    /** A provider-owned, versioned public message. Its text never comes from protected facts. */
    public record PublicDiagnostic(ResourceCommandErrorCategory category, String code, String message) {
        public PublicDiagnostic {
            Objects.requireNonNull(category, "category");
            code = bounded(code, 128, "code");
            message = bounded(message, 512, "message");
        }
    }

    public record Item(BulkTargetEligibility.Decision decision, List<ResourceCommandMessage> diagnostics) {
        public Item {
            Objects.requireNonNull(decision, "decision");
            diagnostics = BulkResponseChecks.diagnostics(diagnostics, decision == BulkTargetEligibility.Decision.BLOCKED);
            if (decision == BulkTargetEligibility.Decision.EXECUTABLE && !diagnostics.isEmpty())
                throw new IllegalArgumentException("Executable preview has diagnostics");
        }
    }

    private final String evaluationFingerprint;
    private final String revision;
    private final String state;
    private final List<PublicDiagnostic> publicDiagnostics;
    private final List<Item> items;

    private BulkPreviewProjection(BulkEvaluationSnapshot evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evaluation.hasTypedEligibility()) throw new IllegalArgumentException("Legacy eligibility cannot be projected");
        this.evaluationFingerprint = evaluation.fingerprint();
        this.revision = null;
        this.state = "UNAVAILABLE";
        this.publicDiagnostics = List.of();
        this.items = List.of();
    }

    /** Explicit provider decision when no safe projector is available for this evaluation. */
    public static BulkPreviewProjection unavailable(BulkEvaluationSnapshot evaluation) {
        return new BulkPreviewProjection(evaluation);
    }

    /**
     * The provider supplies an explicit allowlist for the operation/revision. A protected
     * diagnostic without a matching public definition makes the entire projection unavailable;
     * the private message, target and metadata are never copied into a public row.
     */
    public BulkPreviewProjection(BulkEvaluationSnapshot evaluation, String revision,
            List<PublicDiagnostic> publicDiagnostics) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evaluation.hasTypedEligibility()) throw new IllegalArgumentException("Legacy eligibility cannot be projected");
        this.revision = bounded(revision, 128, "revision");
        this.evaluationFingerprint = evaluation.fingerprint();
        this.state = "COMPLETE";
        Objects.requireNonNull(publicDiagnostics, "publicDiagnostics");
        if (publicDiagnostics.size() > 64) throw new IllegalArgumentException("Too many public diagnostics");
        Map<Key, PublicDiagnostic> allowlist = new HashMap<>();
        for (var definition : publicDiagnostics) {
            Objects.requireNonNull(definition, "public diagnostic");
            if (allowlist.putIfAbsent(new Key(definition.category(), definition.code()), definition) != null)
                throw new IllegalArgumentException("Duplicate public diagnostic");
        }
        this.publicDiagnostics = List.copyOf(publicDiagnostics);
        List<Item> projected = new ArrayList<>(evaluation.targets().size());
        for (var evidence : evaluation.targets()) {
            var eligibility = evidence.eligibility().orElseThrow();
            List<ResourceCommandMessage> safe = new ArrayList<>();
            for (var diagnostic : eligibility.diagnostics()) {
                var definition = allowlist.get(new Key(diagnostic.category(), diagnostic.code()));
                if (definition == null) throw new IllegalArgumentException("Unlisted evaluation diagnostic");
                safe.add(new ResourceCommandMessage(definition.category(), definition.code(),
                        definition.message(), null, Map.of()));
            }
            projected.add(new Item(eligibility.decision(), safe));
        }
        this.items = List.copyOf(projected);
    }

    String evaluationFingerprint() { return evaluationFingerprint; }
    String revision() { return revision; }
    String state() { return state; }
    List<PublicDiagnostic> publicDiagnostics() { return publicDiagnostics; }
    List<Item> items() { return items; }

    void requireMatches(BulkEvaluationSnapshot evaluation) {
        if (!evaluation.fingerprint().equals(evaluationFingerprint)
                || !evaluation.hasTypedEligibility())
            throw new IllegalArgumentException("Preview differs from protected evaluation");
        if (state.equals("UNAVAILABLE")) {
            if (!items.isEmpty() || revision != null || !publicDiagnostics.isEmpty())
                throw new IllegalArgumentException("Unavailable preview contains public data");
            return;
        }
        if (items.size() != evaluation.targets().size())
            throw new IllegalArgumentException("Preview differs from protected evaluation");
        for (int ordinal = 0; ordinal < items.size(); ordinal++) {
            var eligibility = evaluation.targets().get(ordinal).eligibility().orElseThrow();
            var item = items.get(ordinal);
            if (item.decision() != eligibility.decision()
                    || item.diagnostics().size() != eligibility.diagnostics().size())
                throw new IllegalArgumentException("Preview decision differs from evaluation");
            for (int index = 0; index < item.diagnostics().size(); index++) {
                var safe = item.diagnostics().get(index);
                var protectedMessage = eligibility.diagnostics().get(index);
                if (safe.category() != protectedMessage.category()
                        || !safe.code().equals(protectedMessage.code()) || safe.target() != null
                        || !safe.metadata().isEmpty())
                    throw new IllegalArgumentException("Preview diagnostic differs from evaluation");
            }
        }
    }

    private record Key(ResourceCommandErrorCategory category, String code) { }

    private static String bounded(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid preview " + name);
        return value;
    }
}
