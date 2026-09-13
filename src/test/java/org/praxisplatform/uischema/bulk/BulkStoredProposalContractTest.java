package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.proposal;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.snapshot;

class BulkStoredProposalContractTest {
    @Test void reservedIntegerCodecCannotPersistAStringWireIdentity() {
        var input = snapshot(BulkMode.UNIFORM_UPDATE, textCodecClaiming("integer"), "\"42\"", "1.0");

        assertThatThrownBy(() -> proposal(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Protected storage requires canonical wire identities");
    }

    @Test void reservedUuidCodecCannotPersistAnInvalidUuid() {
        var input = snapshot(BulkMode.DOMAIN_COMMAND, textCodecClaiming("uuid"), "\"not-a-uuid\"", "1.0");

        assertThatThrownBy(() -> proposal(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Protected storage requires canonical wire identities");
    }

    @Test void perItemIdentitiesUseTheSameCanonicalCheck() {
        var input = snapshot(BulkMode.PER_ITEM_UPDATE, textCodecClaiming("integer"), "\"42\"", "1.0");

        assertThatThrownBy(() -> proposal(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Protected storage requires canonical wire identities");
    }

    @Test void builtInAndCompatibleCustomCodecsRemainPersistible() {
        var builtIn = proposal(snapshot(BulkMode.UNIFORM_UPDATE, BulkIdentityCodecs.integers(), "42", "1.0"));
        var compatibleCustom = proposal(snapshot(BulkMode.PER_ITEM_UPDATE, textCodecClaiming("string"), "\"42\"", "1.0"));

        for (var value : new BulkStoredProposal[]{builtIn, compatibleCustom}) {
            var restored = BulkSnapshotStorageCodec.decode(
                    BulkSnapshotStorageCodec.encode(value.snapshot()), value.snapshot().fingerprint());
            assertThat(restored.fingerprint()).isEqualTo(value.snapshot().fingerprint());
        }
    }

    private static BulkIdentityCodec<String, String> textCodecClaiming(String codecId) {
        return new BulkIdentityCodec<>() {
            @Override public String readWire(JsonNode node) {
                if (node == null || !node.isTextual()) throw new IllegalArgumentException("Text identity required");
                return node.textValue();
            }
            @Override public String decode(String wire) { return wire; }
            @Override public String encode(String id) { return id; }
            @Override public Schema<?> wireSchema() { return new StringSchema(); }
            @Override public String codecId() { return codecId; }
        };
    }
}
