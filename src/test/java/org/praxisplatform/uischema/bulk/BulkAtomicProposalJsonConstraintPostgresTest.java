package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL metadata invariant only; full snapshot/manifest byte preservation has separate storage proofs. */
class BulkAtomicProposalJsonConstraintPostgresTest {
    private static final String NS = "tenant:prod:payroll";

    @Test void opaqueEscapedNulIsAcceptedButMissingNullOrDivergentAtomicityIsRejected() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrate(owner, Map.of(NS, BulkPostgresTestSupport.DEPLOYMENT_ID));
            BulkPostgresTestSupport.ready(owner, NS, "employee-bulk-approve");
            var sql = new JdbcTemplate(owner);
            var mapper = new ObjectMapper();
            var document = mapper.createObjectNode().put("atomicity", "PER_ITEM");
            document.putObject("parameters").put("reason", "opaque\u0000value")
                    .put("key\u0000name", "literal\\u0000value");
            byte[] bytes = mapper.writeValueAsBytes(document);
            UUID accepted = insert(sql, bytes, "PER_ITEM");
            assertThat(sql.queryForObject("select payload from praxis_bulk.praxis_bulk_proposal where proposal_id=?",
                    byte[].class, accepted)).containsExactly(bytes);
            assertThat(sql.queryForObject("select protocol_version from praxis_bulk.praxis_bulk_proposal where proposal_id=?",
                    Integer.class, accepted)).isEqualTo(2);

            for (String invalid : new String[] {"{}", "{\"atomicity\":null}", "{\"atomicity\":1}",
                    "{\"atomicity\":{}}", "{\"atomicity\":[]}", "{\"atomicity\":\"OTHER\"}",
                    "{\"atomicity\":\"ATOMIC\"}", "{\"atomicity\":\"PER_ITEM\\u0000\"}",
                    "{\"atomicity\\u0000\":\"PER_ITEM\"}"}) {
                assertThatThrownBy(() -> insert(sql, invalid.getBytes(StandardCharsets.UTF_8), "PER_ITEM"))
                        .as("invalid metadata: %s", invalid).hasRootCauseInstanceOf(PSQLException.class)
                        .satisfies(error -> assertThat(((PSQLException) error.getCause()).getSQLState()).isEqualTo("23514"));
            }
            assertThatThrownBy(() -> insert(sql, "not-json".getBytes(StandardCharsets.UTF_8), "PER_ITEM"))
                    .hasRootCauseInstanceOf(PSQLException.class);
            assertThatThrownBy(() -> insert(sql, new byte[] {(byte) 0xff}, "PER_ITEM"))
                    .hasRootCauseInstanceOf(PSQLException.class);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class)).isEqualTo(1);

        }
    }

    @Test void catalogRejectsNullableEqualityInsteadOfFailClosedProjection() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrate(owner, Map.of(NS, BulkPostgresTestSupport.DEPLOYMENT_ID));
            var sql = new JdbcTemplate(owner);
            BulkExecutionMigrator.validate(owner);
            sql.execute("alter table praxis_bulk.praxis_bulk_proposal drop constraint praxis_bulk_proposal_atomicity_check");
            sql.execute("alter table praxis_bulk.praxis_bulk_proposal add constraint praxis_bulk_proposal_atomicity_check "
                    + "check (atomicity in ('PER_ITEM','ATOMIC') and atomicity=convert_from(payload,'UTF8')::json->>'atomicity')");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("praxis_bulk_proposal_atomicity_check");
        }
    }

    private static UUID insert(JdbcTemplate sql, byte[] payload, String atomicity) {
        UUID id = UUID.randomUUID();
        sql.update("""
                insert into praxis_bulk.praxis_bulk_proposal
                (proposal_id,namespace_id,subject_id,resource_key,operation_id,created_at,expires_at,
                 fingerprint,payload,atomicity,protocol_version,control_generation,
                 control_descriptor_fingerprint,control_structural_revision)
                values (?,?,'operator','employees','employee-bulk-approve',clock_timestamp(),
                        clock_timestamp()+interval '60 seconds',?,?,?,2,
                        (select generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id='employee-bulk-approve'),
                        ?, 'structural-r1')
                """, id, NS, "sha256:" + "a".repeat(64), payload, atomicity, NS, "sha256:" + "0".repeat(64));
        return id;
    }
}
