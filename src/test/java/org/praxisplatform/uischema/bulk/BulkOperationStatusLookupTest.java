package org.praxisplatform.uischema.bulk;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkOperationStatusLookupTest {
    @Test void resolvesOnlyTheCurrentNamespaceResourceAndDeclaredConfirmation() throws Exception {
        var infrastructure = mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var discovery = mock(BulkOperationIdentityDiscovery.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(infrastructure.namespace()).thenReturn("tenant-a");
        when(discovery.discover("tenant-a","items")).thenReturn(List.of(new BulkOperationControlIdentity("tenant-a","items.bulk.update")));
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false,true,false);
        when(rows.getString(1)).thenReturn("items.bulk.update");
        when(infrastructure.withConsistentRead(any())).thenAnswer(call -> ((ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection));
        var lookup = new BulkOperationStatusLookup(infrastructure,discovery);
        var policy = allowedPolicy(infrastructure);
        var id = UUID.randomUUID();
        assertEquals("items.bulk.update",lookup.proposalOperationId("items",id,policy));
        assertEquals("items.bulk.update",lookup.executionOperationId("items",id,policy));
        verify(query,times(2)).setString(1,"tenant-a");
        verify(query,times(2)).setString(2,"items");
        verify(query,times(2)).setObject(3,id);
        verify(connection).prepareStatement(contains("proposal_id=?"));
        verify(connection).prepareStatement(contains("execution_id=?"));
        assertEquals(404,assertThrows(ResponseStatusException.class, () -> lookup.proposalOperationId("another-resource",id,policy)).getStatusCode().value());
        verify(infrastructure,times(2)).withConsistentRead(any());
    }

    @Test void unknownAndUndeclaredOperationsHaveTheSameNonEnumeratingFailure() throws Exception {
        var infrastructure = mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var discovery = mock(BulkOperationIdentityDiscovery.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(infrastructure.namespace()).thenReturn("tenant-a");
        when(discovery.discover("tenant-a","items")).thenReturn(List.of(new BulkOperationControlIdentity("tenant-a","items.bulk.update")));
        var connection = mock(Connection.class); var query = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(query); when(query.executeQuery()).thenReturn(rows);
        when(infrastructure.withConsistentRead(any())).thenAnswer(call -> ((ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection));
        var lookup = new BulkOperationStatusLookup(infrastructure,discovery);
        var policy = allowedPolicy(infrastructure);
        when(rows.next()).thenReturn(false);
        var missing = assertThrows(ResponseStatusException.class, () -> lookup.proposalOperationId("items",UUID.randomUUID(),policy));
        when(rows.next()).thenReturn(true); when(rows.getString(1)).thenReturn("undeclared");
        var undeclared = assertThrows(ResponseStatusException.class, () -> lookup.executionOperationId("items",UUID.randomUUID(),policy));
        assertEquals(missing.getStatusCode(),undeclared.getStatusCode());
        assertEquals(missing.getReason(),undeclared.getReason());
    }
    @Test void missingOrUnavailableAuthorityDoesNotQueryProtectedIds() throws Exception {
        for (var decision : Arrays.asList(BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE,null)) {
            var fixture=fixture();
            var authority=fixture.policy().readAuthorization("items.bulk.update");
            when(authority.preAuthorize(any(),any())).thenReturn(decision);
            assertEquals(503,assertThrows(ResponseStatusException.class, () -> fixture.lookup()
                    .proposalOperationId("items",UUID.randomUUID(),fixture.policy())).getStatusCode().value());
            verify(fixture.connection(),never()).prepareStatement(anyString());
        }
    }

    @Test void wrongInfrastructureBindingFailsBeforeOpeningTheReadSnapshot() throws Exception {
        var fixture=fixture();
        when(fixture.policy().readAuthorization("items.bulk.update").executionInfrastructure()).thenReturn(
                mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)));
        assertEquals(503,assertThrows(ResponseStatusException.class, () -> fixture.lookup()
                .executionOperationId("items",UUID.randomUUID(),fixture.policy())).getStatusCode().value());
        verify(fixture.infrastructure(),never()).withConsistentRead(any());
        verify(fixture.connection(),never()).prepareStatement(anyString());
    }

    @Test void partialGrantFiltersTheCorrelationQueryToTheAuthorizedOperations() throws Exception {
        var fixture=fixture();
        String denied="items.bulk.denied";
        when(fixture.discovery().discover("tenant-a","items")).thenReturn(List.of(
                new BulkOperationControlIdentity("tenant-a","items.bulk.update"),new BulkOperationControlIdentity("tenant-a",denied)));
        var authority=mock(BulkReadAuthorizationProvider.class);
        when(authority.executionInfrastructure()).thenReturn(fixture.infrastructure());
        when(authority.confirmationOperationId()).thenReturn(denied);
        when(authority.preAuthorize(any(),any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.DENIED);
        var original=fixture.policy().context("items.bulk.update");
        when(fixture.policy().context(denied)).thenReturn(new BulkFingerprintContext(original.namespaceId(),original.subjectId(),"items",
                new org.praxisplatform.uischema.openapi.CanonicalOperationRef("items",denied,"/items/denied","POST"),original.schemaRevision(),original.atomicity()));
        when(fixture.policy().readAuthorization(denied)).thenReturn(authority);
        var statement=mock(PreparedStatement.class); var rows=mock(ResultSet.class);
        when(fixture.connection().prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getString(1)).thenReturn(denied);
        assertEquals(404,assertThrows(ResponseStatusException.class, () -> fixture.lookup()
                .executionOperationId("items",UUID.randomUUID(),fixture.policy())).getStatusCode().value());
        verify(fixture.connection()).prepareStatement(contains("operation_id in (?)"));
        verify(statement).setString(4,"items.bulk.update");
        verify(statement,never()).setString(5,denied);
        var order=inOrder(fixture.policy().readAuthorization("items.bulk.update"),fixture.connection());
        order.verify(fixture.policy().readAuthorization("items.bulk.update")).preAuthorize(any(),any());
        order.verify(fixture.connection()).prepareStatement(anyString());
    }

    private record Fixture(BulkExecutionInfrastructure infrastructure,BulkOperationIdentityDiscovery discovery,
                           BulkResourcePolicy policy,Connection connection,BulkOperationStatusLookup lookup) { }
    private static Fixture fixture() {
        var infrastructure=mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var discovery=mock(BulkOperationIdentityDiscovery.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var connection=mock(Connection.class);
        when(infrastructure.namespace()).thenReturn("tenant-a");
        when(discovery.discover("tenant-a","items")).thenReturn(List.of(new BulkOperationControlIdentity("tenant-a","items.bulk.update")));
        when(infrastructure.withConsistentRead(any())).thenAnswer(call -> ((ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection));
        return new Fixture(infrastructure,discovery,allowedPolicy(infrastructure),connection,new BulkOperationStatusLookup(infrastructure,discovery));
    }

    private static BulkResourcePolicy allowedPolicy(BulkExecutionInfrastructure infrastructure) {
        var policy=mock(BulkResourcePolicy.class);
        var authorization=mock(BulkReadAuthorizationProvider.class);
        when(authorization.executionInfrastructure()).thenReturn(infrastructure);
        when(authorization.confirmationOperationId()).thenReturn("items.bulk.update");
        when(authorization.preAuthorize(any(),any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.ALLOWED);
        when(policy.readAuthorization("items.bulk.update")).thenReturn(authorization);
        when(policy.context("items.bulk.update")).thenReturn(new BulkFingerprintContext("tenant-a","subject-a","items",
                new org.praxisplatform.uischema.openapi.CanonicalOperationRef("items","items.bulk.update","/items/bulk","POST"),
                "schema-a",org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM));
        return policy;
    }
}
