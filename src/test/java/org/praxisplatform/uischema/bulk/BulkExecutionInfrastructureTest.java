package org.praxisplatform.uischema.bulk;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkExecutionInfrastructureTest {
    @Test
    void explicitNamespaceAndBindingAreRequiredWithoutDatabaseAccess() {
        var ds = mock(DataSource.class); var manager = new DataSourceTransactionManager(ds);
        var binding = new BulkExecutionInfrastructure(ds, manager, "tenant-a:prod", "deployment-prod");
        assertThat(binding.dataSource()).isSameAs(ds);
        assertThat(binding.transactionManager()).isSameAs(manager);
        assertThat(binding.namespace()).isEqualTo("tenant-a:prod");
        assertThat(binding.deploymentId()).isEqualTo("deployment-prod");
        for (String invalid : new String[] {null, "", " ", " leading", "trailing ", "tab\tvalue", "x".repeat(201)}) {
            assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, manager, invalid, "deployment-prod")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[] {null, "", " ", " leading", "trailing ", "tab\tvalue", "x".repeat(201)}) {
            assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, manager, "tenant-a:prod", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(ds);
    }

    @Test
    void rejectsUnsupportedOrMismatchedManagersAndUnprovenWrappers() {
        var ds = mock(DataSource.class);
        assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, mock(PlatformTransactionManager.class), "x", "deployment-x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, new DataSourceTransactionManager(mock(DataSource.class)), "x", "deployment-x"))
                .isInstanceOf(IllegalArgumentException.class);
        for (var wrapper : new DataSource[] {new DelegatingDataSource(ds), mock(AbstractRoutingDataSource.class)}) {
            assertThatThrownBy(() -> new BulkExecutionInfrastructure(wrapper, new DataSourceTransactionManager(wrapper), "x", "deployment-x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(ds);
    }

    @Test
    void jpaManagerCannotClaimADifferentDatasourceThanItsEntityManagerFactory() {
        var ds = mock(DataSource.class);
        var emf = mock(EntityManagerFactory.class, withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
        var manager = new JpaTransactionManager(); manager.setEntityManagerFactory(emf); manager.setDataSource(ds);
        when(((EntityManagerFactoryInfo) emf).getDataSource()).thenReturn(mock(DataSource.class));
        assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, manager, "x", "deployment-x")).isInstanceOf(IllegalArgumentException.class);
        when(((EntityManagerFactoryInfo) emf).getDataSource()).thenReturn(ds);
        assertThat(new BulkExecutionInfrastructure(ds, manager, "x", "deployment-x").dataSource()).isSameAs(ds);
        var opaque = new JpaTransactionManager(); opaque.setEntityManagerFactory(mock(EntityManagerFactory.class)); opaque.setDataSource(ds);
        assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, opaque, "x", "deployment-x")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ds);
    }

    @Test
    void rejectsDisabledRollbackOnParticipationFailureAtConstructionAndBeforeWork() {
        var ds = mock(DataSource.class); var manager = new DataSourceTransactionManager(ds);
        var binding = new BulkExecutionInfrastructure(ds, manager, "x", "deployment-x");
        manager.setGlobalRollbackOnParticipationFailure(false);
        assertThatThrownBy(() -> new BulkExecutionInfrastructure(ds, manager, "x", "deployment-x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding.withConnection(connection -> { throw new AssertionError("must not run"); }))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ds);
    }

    @Test
    void rebindingTheMutableHostManagerIsRejectedBeforeWork() {
        var ds = mock(DataSource.class); var manager = new DataSourceTransactionManager(ds);
        var binding = new BulkExecutionInfrastructure(ds, manager, "x", "deployment-x");
        manager.setDataSource(mock(DataSource.class));
        assertThatThrownBy(() -> binding.withConnection(connection -> { throw new AssertionError("must not run"); }))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ds);
    }
}
