package org.praxisplatform.uischema.bulk;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Explicit PostgreSQL grantees trusted for the bulk runtime and retention operator. */
public record BulkExecutionRoleConfiguration(
        String expectedSchemaOwnerRole,
        Set<String> runtimeGranteeRoles,
        Set<String> retentionExecutorMembers) {

    public BulkExecutionRoleConfiguration {
        expectedSchemaOwnerRole = canonicalOwner(expectedSchemaOwnerRole);
        runtimeGranteeRoles = canonicalRoles(runtimeGranteeRoles, "runtimeGranteeRoles");
        retentionExecutorMembers = canonicalRoles(retentionExecutorMembers, "retentionExecutorMembers");
        var overlap = new LinkedHashSet<>(runtimeGranteeRoles);
        overlap.retainAll(retentionExecutorMembers);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("Runtime and retention executor roles must be disjoint");
        }
        if (runtimeGranteeRoles.contains(expectedSchemaOwnerRole)
                || retentionExecutorMembers.contains(expectedSchemaOwnerRole)) {
            throw new IllegalArgumentException("The schema owner cannot be a runtime or retention executor role");
        }
        for (String role : Set.of("praxis_bulk_retention_owner", "praxis_bulk_retention_executor")) {
            if (runtimeGranteeRoles.contains(role) || retentionExecutorMembers.contains(role)) {
                throw new IllegalArgumentException("Internal bulk retention roles cannot be configured as host roles");
            }
        }
    }

    public static BulkExecutionRoleConfiguration none(String expectedSchemaOwnerRole) {
        return new BulkExecutionRoleConfiguration(expectedSchemaOwnerRole, Set.of(), Set.of());
    }

    private static String canonicalOwner(String role) {
        if (role == null || role.isBlank() || !role.equals(role.strip())
                || role.codePoints().anyMatch(Character::isISOControl)
                || role.getBytes(StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("expectedSchemaOwnerRole must be a canonical PostgreSQL role name");
        }
        return role;
    }

    private static Set<String> canonicalRoles(Set<String> roles, String name) {
        Objects.requireNonNull(roles, name);
        var result = new LinkedHashSet<String>();
        for (String role : roles) {
            if (role == null || role.isBlank() || !role.equals(role.strip())
                    || role.codePoints().anyMatch(Character::isISOControl)
                    || role.getBytes(StandardCharsets.UTF_8).length > 63) {
                throw new IllegalArgumentException(name + " must contain canonical PostgreSQL role names");
            }
            if (!result.add(role)) throw new IllegalArgumentException(name + " contains duplicate roles");
        }
        return Set.copyOf(result);
    }
}
