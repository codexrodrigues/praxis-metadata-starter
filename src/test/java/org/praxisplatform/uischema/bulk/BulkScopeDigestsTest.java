package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BulkScopeDigestsTest {
    @Test
    void quotaIdentityIsPerAuthenticatedSubjectAndDeploymentNotPerOperation() {
        var first = BulkScopeDigests.subjectQuotaDigest("deployment-a", "principal-42");
        var sameIdentity = BulkScopeDigests.subjectQuotaDigest("deployment-a", "principal-42");

        assertThat(first).isEqualTo(sameIdentity).matches("sha256:[0-9a-f]{64}");
        assertThat(first).isNotEqualTo(BulkScopeDigests.subjectQuotaDigest("deployment-b", "principal-42"));
        assertThat(first).isNotEqualTo(BulkScopeDigests.subjectQuotaDigest("deployment-a", "principal-43"));
    }

    @Test
    void authorizationScopeIsVersionedAndDistinctFromQuotaIdentity() {
        var authorization = BulkScopeDigests.authorizationScopeDigest(
                "tenant-a:production:api", "principal-42", "payroll.events", "payroll.approve");
        var otherOperation = BulkScopeDigests.authorizationScopeDigest(
                "tenant-a:production:api", "principal-42", "payroll.events", "payroll.reject");

        assertThat(authorization).matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(otherOperation)
                .isNotEqualTo(BulkScopeDigests.subjectQuotaDigest("deployment-a", "principal-42"));
        assertThatThrownBy(() -> BulkScopeDigests.authorizationScopeDigest(
                "tenant-a:production:api", "  principal-42", "payroll.events", "payroll.approve"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
