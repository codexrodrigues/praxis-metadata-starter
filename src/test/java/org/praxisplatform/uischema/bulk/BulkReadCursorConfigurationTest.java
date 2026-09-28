package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

class BulkReadCursorConfigurationTest {
    @Test void effectiveAuthorizationFingerprintUsesStableVersionedLengthFraming() {
        byte[] provider = new byte[32];
        for (int index = 0; index < provider.length; index++) provider[index] = (byte) index;

        assertThat(HexFormat.of().formatHex(BulkAuthorizedProposalResultsReader
                .effectiveAuthorizationFingerprint("delegado-ação", provider)))
                .isEqualTo("f7ae74d0a3ebb5eddae64b20fbf63f8f6807de9d42bdb42abef847232655e5a9");
        assertThat(BulkAuthorizedProposalResultsReader
                .effectiveAuthorizationFingerprint("delegado-ação", provider))
                .isNotEqualTo(BulkAuthorizedProposalResultsReader
                        .effectiveAuthorizationFingerprint("delegado-acao", provider));
    }

    @Test void snapshotsKeysBoundsLifetimeAndNeverSerializesOrLogsSecrets() throws Exception {
        byte[] material = bytes(7);
        Map<String, javax.crypto.SecretKey> keys = new HashMap<>();
        keys.put("active", new SecretKeySpec(material, "AES"));
        var configuration = new BulkReadCursorConfiguration(
                "active", keys, Duration.ofMinutes(15));
        material[0] = 99;
        keys.clear();

        assertThat(configuration.keySet().activeKey().getEncoded()).containsExactly(bytes(7));
        assertThat(configuration.ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(configuration.toString()).isEqualTo("BulkReadCursorConfiguration[protected]");
        assertThat(new ObjectMapper().writeValueAsString(new ConfigurationHolder(configuration)))
                .isEqualTo("{}");
        assertThat(configuration.toString()).doesNotContain("active", "070707");

        assertThatThrownBy(() -> new BulkReadCursorConfiguration(
                "active", Map.of("active", new SecretKeySpec(bytes(1), "AES")), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorConfiguration(
                "active", Map.of("active", new SecretKeySpec(bytes(1), "AES")), Duration.ofMinutes(15).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkReadCursorConfiguration(
                "missing", Map.of("active", new SecretKeySpec(bytes(1), "AES")), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void publicProposalItemAllowsOnlyCanonicalIdentityAndAllowlistedDiagnosticShape() throws Exception {
        var diagnostic = new ResourceCommandMessage(ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "STATE_NOT_ALLOWED", "State changed", null, Map.of());
        var executable = new BulkProposalItemResult<>(42,
                BulkProposalItemResult.Decision.EXECUTABLE, List.of());
        var blocked = new BulkProposalItemResult<>("9007199254740993",
                BulkProposalItemResult.Decision.BLOCKED, List.of(diagnostic));

        var mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(executable))
                .isEqualTo("{\"id\":42,\"decision\":\"EXECUTABLE\",\"diagnostics\":[]}");
        assertThat(mapper.writeValueAsString(blocked)).doesNotContain("/private", "\"private\"");
        assertThatThrownBy(() -> blocked.diagnostics().add(diagnostic))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(blocked.toString()).doesNotContain("9007199254740993", "State changed");

        assertThatThrownBy(() -> new BulkProposalItemResult<>(42L,
                BulkProposalItemResult.Decision.EXECUTABLE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkProposalItemResult<>("",
                BulkProposalItemResult.Decision.EXECUTABLE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkProposalItemResult<>(1,
                BulkProposalItemResult.Decision.BLOCKED, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkProposalItemResult<>(1,
                BulkProposalItemResult.Decision.EXECUTABLE, List.of(diagnostic)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkProposalItemResult<>(1,
                BulkProposalItemResult.Decision.BLOCKED,
                java.util.Collections.nCopies(17, diagnostic)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkProposalItemResult<>(1,
                BulkProposalItemResult.Decision.BLOCKED, List.of(new ResourceCommandMessage(
                        ResourceCommandErrorCategory.CONFLICT_DEPENDENCY, "STATE_NOT_ALLOWED",
                        "State changed", "/private", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        String invalidExecutable = mapper.writeValueAsString(blocked)
                .replace("\"BLOCKED\"", "\"EXECUTABLE\"");
        assertThatThrownBy(() -> mapper.readValue(invalidExecutable, BulkProposalItemResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkProposalItemResult<>(1,
                BulkProposalItemResult.Decision.BLOCKED, List.of(new ResourceCommandMessage(
                        ResourceCommandErrorCategory.CONFLICT_DEPENDENCY, "STATE_NOT_ALLOWED",
                        "State changed", null, Map.of("private", Instant.EPOCH)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private record ConfigurationHolder(BulkReadCursorConfiguration cursor) { }
}
