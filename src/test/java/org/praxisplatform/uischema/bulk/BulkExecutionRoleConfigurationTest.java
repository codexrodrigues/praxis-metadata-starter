package org.praxisplatform.uischema.bulk;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkExecutionRoleConfigurationTest {
    @Test
    void canonicalizesDefensiveRoleSets() {
        var roles = new BulkExecutionRoleConfiguration("praxis_migrator",
                Set.of("praxis_runtime"), Set.of("praxis_retention"));

        assertThat(roles.runtimeGranteeRoles()).containsExactly("praxis_runtime");
        assertThat(roles.retentionExecutorMembers()).containsExactly("praxis_retention");
        assertThatThrownBy(() -> roles.runtimeGranteeRoles().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNoncanonicalOrPrivilegedRoleIdentities() {
        assertThatThrownBy(() -> new BulkExecutionRoleConfiguration("migrator", Set.of(" runtime"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkExecutionRoleConfiguration("migrator", Set.of("bulk\nrole"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkExecutionRoleConfiguration("migrator", Set.of("a".repeat(64)), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkExecutionRoleConfiguration(
                "migrator", Set.of("same"), Set.of("same"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkExecutionRoleConfiguration(
                "migrator", Set.of("praxis_bulk_retention_owner"), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkExecutionRoleConfiguration(
                "runtime", Set.of("runtime"), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
