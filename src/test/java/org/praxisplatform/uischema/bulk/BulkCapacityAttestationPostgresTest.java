package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real authority V2 function/ACL proof with rights issued by the V1 allocator. */
class BulkCapacityAttestationPostgresTest {
    @Test
    void exactAttestationAndIndividualRightAreImmutableAndReaderOnly() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            sql.execute("create role capacity_v2_provisioner login");
            sql.execute("create role capacity_v2_allocator login");
            sql.execute("create role capacity_v2_reader login");
            var identity = new BulkCapacityAuthorityMigrator.Identity("deployment-v2", "prod",
                    UUID.randomUUID(), 17);
            var roles = new BulkCapacityAuthorityMigrator.RoleConfiguration("postgres",
                    "capacity_v2_provisioner", "capacity_v2_allocator", "capacity_v2_reader");
            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, identity, roles)).isEqualTo(2);
            var provisionerSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl("capacity_v2_provisioner", "postgres"), "capacity_v2_provisioner", "");
            var allocatorSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl("capacity_v2_allocator", "postgres"), "capacity_v2_allocator", "");
            var readerSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl("capacity_v2_reader", "postgres"), "capacity_v2_reader", "");
            var provisioner = new BulkCapacityAuthorityInfrastructure(provisionerSource,
                    new DataSourceTransactionManager(provisionerSource), identity,
                    BulkCapacityAuthorityInfrastructure.Access.PROVISIONER, roles);
            var allocator = new BulkCapacityAuthorityInfrastructure(allocatorSource,
                    new DataSourceTransactionManager(allocatorSource), identity,
                    BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR, roles);
            var reader = new BulkCapacityAuthorityInfrastructure(readerSource,
                    new DataSourceTransactionManager(readerSource), identity,
                    BulkCapacityAuthorityInfrastructure.Access.READER, roles);
            var issued = new JdbcBulkCapacityIssuer(allocator, reader);
            var readback = new JdbcBulkCapacityIssuer.CapacityReader(reader);
            provisioner.enrollBinding("tenant-a", "binding-a", 3);
            var request = new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(), identity.deploymentId(),
                    "tenant-a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1);
            issued.requestCapacity(request);
            var token = issued.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
            assertThat(readback.readIssuedToken(token.tokenId())).isEmpty();
            UUID database = UUID.randomUUID();
            UUID attestation = UUID.randomUUID();
            provisioner.registerAttestation("tenant-a", "binding-a", 3, database, attestation);
            var authoritative = readback.readAttestation(attestation).orElseThrow();
            assertThat(authoritative).isEqualTo(new JdbcBulkCapacityIssuer.Attestation(attestation,
                    database, "binding-a", identity.deploymentId(), "tenant-a", identity.environment(),
                    3, identity.authorityId(), identity.expectedAuthorityEpoch()));
            var right = readback.readIssuedToken(token.tokenId()).orElseThrow();
            assertThat(right.tokenId()).isEqualTo(token.tokenId());
            assertThat(right.requestId()).isEqualTo(request.requestId());
            assertThat(right.databaseId()).isEqualTo(database);
            assertThat(right.attestationId()).isEqualTo(attestation);
            assertThat(right.bindingGeneration()).isEqualTo(3);
            assertThat(right.authorityId()).isEqualTo(identity.authorityId());
            assertThat(right.authorityEpoch()).isEqualTo(identity.expectedAuthorityEpoch());
            assertThat(right.payloadDigest()).matches("sha256:[0-9a-f]{64}");
            assertThat(right.tokenState()).isEqualTo("ISSUED");
            provisioner.registerAttestation("tenant-a", "binding-a", 3, database, attestation);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk_capacity.binding_attestation",
                    Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> provisioner.registerAttestation("tenant-a", "binding-a", 4,
                    database, attestation)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> provisioner.registerAttestation("tenant-a", "binding-a", 3,
                    UUID.randomUUID(), attestation)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> provisioner.registerAttestation("tenant-a", "binding-a", 3,
                    database, UUID.randomUUID())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(readerSource).update(
                    "delete from praxis_bulk_capacity.binding_attestation"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(allocatorSource).queryForList(
                    "select * from praxis_bulk_capacity.read_issued_token(?)", token.tokenId()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(provisionerSource).queryForList(
                    "select * from praxis_bulk_capacity.read_issued_token(?)", token.tokenId()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(readerSource).queryForList(
                    "select praxis_bulk_capacity.register_attestation(?,?,?,?,?,?,?)",
                    identity.deploymentId(), "tenant-a", identity.environment(), "binding-a",
                    3, database, attestation)).isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk_capacity.binding_attestation",
                    Integer.class)).isEqualTo(1);
        }
    }
}
