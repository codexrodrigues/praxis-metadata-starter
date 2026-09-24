package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drift must be rejected even when Flyway history and migration checksums remain intact. */
class BulkDurableMigrationPostgresTest {
    @Test
    void validatesPhysicalDurabilityAndBindingInsteadOfTrustingMigrationHistory() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            for (String mutation : List.of(
                    "alter table praxis_bulk.praxis_bulk_item_receipt set unlogged",
                    "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_binding",
                    "alter table praxis_bulk.praxis_bulk_item_receipt disable trigger praxis_bulk_item_receipt_reject_mutation",
                    "alter table praxis_bulk.praxis_bulk_execution alter column owner_epoch drop not null",
                    "alter table praxis_bulk.praxis_bulk_execution alter column next_ordinal set default 0",
                    "alter table praxis_bulk.praxis_bulk_execution enable row level security",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_epoch_check; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_epoch_check check (owner_epoch >= 0)",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_proposal_key; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_proposal_key unique (proposal_id) deferrable initially deferred",
                    "alter table praxis_bulk.praxis_bulk_item_receipt drop constraint praxis_bulk_item_receipt_execution_fkey; "
                            + "alter table praxis_bulk.praxis_bulk_item_receipt add constraint praxis_bulk_item_receipt_execution_fkey "
                            + "foreign key (execution_id) references praxis_bulk.praxis_bulk_execution(execution_id) not valid",
                    "alter function praxis_bulk.protect_praxis_bulk_execution_binding() security definer",
                    "create or replace function praxis_bulk.reject_praxis_bulk_item_receipt_mutation() "
                            + "returns trigger language plpgsql as $$begin return new; end;$$",
                    "create function praxis_bulk.protect_praxis_bulk_execution_binding(integer) returns integer language sql as 'select $1'",
                    "create trigger praxis_bulk_execution_protect_binding before update on praxis_bulk.praxis_bulk_item_receipt "
                            + "for each row execute function praxis_bulk.protect_praxis_bulk_execution_binding()",
                    "create table praxis_bulk.unexpected(id integer)",
                    "create type praxis_bulk.unexpected as (id integer)",
                    "create unique index unexpected_execution_owner on praxis_bulk.praxis_bulk_execution(owner_id)",
                    "create index unexpected_expression on praxis_bulk.praxis_bulk_execution ((lower(owner_id)))",
                    "create aggregate praxis_bulk.unexpected_sum(integer) (sfunc=int4pl, stype=integer)",
                    "create rule unexpected_update as on update to praxis_bulk.praxis_bulk_execution do instead nothing",
                    "create policy unexpected_policy on praxis_bulk.praxis_bulk_execution using (true)",
                    "drop index praxis_bulk.praxis_bulk_schema_history_s_idx; "
                            + "create unique index praxis_bulk_schema_history_s_idx on praxis_bulk.praxis_bulk_execution(owner_id)",
                    "create unique index praxis_bulk_fk_bound_owner on praxis_bulk.praxis_bulk_execution(owner_id); "
                            + "create table public.praxis_bulk_external_ref(owner_id text references praxis_bulk.praxis_bulk_execution(owner_id))")) {
                assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(3);
                sql.execute(mutation);
                assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                        .as("reject drift: %s", mutation).isInstanceOf(IllegalStateException.class);
                sql.execute("drop schema praxis_bulk cascade");
            }
        }
    }

    @Test
    void validationIsStableWhenOperationalDatasourceDefaultsToOwnedSchema() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var url = postgres.getJdbcUrl("postgres", "postgres") + "&currentSchema=praxis_bulk";
            try (var physicalConnection = java.sql.DriverManager.getConnection(url, "postgres", "postgres");
                    var scopedDataSource = new SingleConnectionDataSource(physicalConnection, true)) {
                assertThat(BulkExecutionMigrator.migrate(scopedDataSource)).isEqualTo(3);
                BulkExecutionMigrator.validate(scopedDataSource);
                try (var statement = physicalConnection.createStatement();
                        var result = statement.executeQuery("select current_schema(), current_setting('search_path')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("praxis_bulk");
                    assertThat(result.getString(2)).isEqualTo("praxis_bulk");
                }
            }
        }
    }
}
