package org.praxisplatform.consumer;

import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration;
import org.praxisplatform.uischema.bulk.BulkIdentityCodecs;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkExecutionMode;
import org.praxisplatform.uischema.bulk.BulkSelectionMode;
import org.praxisplatform.uischema.bulk.BulkOperationalProfile;
import org.praxisplatform.uischema.bulk.BulkOperationDescriptorProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/** Explicit opt-in bindings used only by the independent consumer fixture. */
@TestConfiguration(proxyBeanMethods = false)
public class ArtifactBulkTestConfiguration {

    private static final String RUNTIME_ROLE = "bulk_consumer_runtime";
    private static final String CONTROL_ROLE = "bulk_consumer_control";

    @Bean
    BulkExecutionRoleConfiguration bulkTestRoles() {
        return new BulkExecutionRoleConfiguration("postgres", Set.of(RUNTIME_ROLE), Set.of(), Set.of(CONTROL_ROLE));
    }

    @Bean("dataSource")
    @Primary
    DataSource runtimeDataSource(@Value("${spring.datasource.url}") String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, RUNTIME_ROLE, "");
    }

    @Bean("transactionManager")
    @Primary
    PlatformTransactionManager runtimeTransactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    BulkExecutionInfrastructure bulkExecutionInfrastructure(@Qualifier("dataSource") DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            BulkExecutionRoleConfiguration bulkTestRoles,
            @Value("${consumer.bulk.namespace}") String namespace,
            @Value("${consumer.bulk.deployment}") String deployment) {
        return new BulkExecutionInfrastructure(dataSource, transactionManager, namespace, deployment, bulkTestRoles);
    }

    @Bean
    DataSource bulkControlDataSource(@Value("${consumer.bulk.control-url}") String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, CONTROL_ROLE, "");
    }

    @Bean
    PlatformTransactionManager bulkControlTransactionManager(@Qualifier("bulkControlDataSource") DataSource bulkControlDataSource) {
        return new DataSourceTransactionManager(bulkControlDataSource);
    }

    @Bean
    BulkControlPlaneInfrastructure bulkControlPlaneInfrastructure(
            @Qualifier("bulkControlDataSource") DataSource bulkControlDataSource,
            @Qualifier("bulkControlTransactionManager") PlatformTransactionManager bulkControlTransactionManager,
            @Value("${consumer.bulk.namespace}") String namespace,
            @Value("${consumer.bulk.deployment}") String deployment,
            BulkExecutionInfrastructure runtime) {
        return new BulkControlPlaneInfrastructure(bulkControlDataSource, bulkControlTransactionManager,
                namespace, deployment, CONTROL_ROLE, runtime);
    }

    @Bean
    BulkOperationDescriptorProvider artifactApprovalProvider(BulkExecutionInfrastructure runtime) {
        return new BulkOperationDescriptorProvider() {
            @Override public String confirmationOperationId() { return ArtifactBulkController.CONFIRMATION; }
            @Override public String providerId() { return "artifact.consumer.approval"; }
            @Override public String providerRevision() { return "fixture-1"; }
            @Override public org.praxisplatform.uischema.bulk.BulkIdentityCodec<?, ?> identityCodec() {
                return BulkIdentityCodecs.strings();
            }
            @Override public String identitySchemaPointer() { return "/properties/targetIds/items"; }
            @Override public BulkOperationalProfile profile() {
                return new BulkOperationalProfile(EnumSet.of(BulkMode.DOMAIN_COMMAND),
                        EnumSet.of(BulkExecutionMode.SYNC), EnumSet.of(BulkSelectionMode.EXPLICIT),
                        200, 1024 * 1024, Duration.ofMinutes(5), Duration.ofSeconds(5));
            }
            @Override public BulkExecutionInfrastructure executionInfrastructure() { return runtime; }
        };
    }
}
