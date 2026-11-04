package org.praxisplatform.uischema.bulk;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P5AStatusAuthorizationReviewTest {
    @SuppressWarnings("unchecked")
    @Test void globalDenialMustNotDiscloseWhetherAnExecutionExists() throws Exception {
        String operation = "p3.items.bulk.update";
        var infrastructure = mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(infrastructure.namespace()).thenReturn("namespace-a");
        var discovery = mock(BulkOperationIdentityDiscovery.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(discovery.discover("namespace-a","p3.items")).thenReturn(List.of(new BulkOperationControlIdentity("namespace-a",operation)));
        var exists = new AtomicBoolean(true); var firstRow = new AtomicBoolean();
        var connection = mock(Connection.class); var query = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenAnswer(call -> { firstRow.set(true); return rows; });
        when(rows.next()).thenAnswer(call -> exists.get() && firstRow.getAndSet(false));
        when(rows.getString(1)).thenReturn(operation);
        when(infrastructure.withConsistentRead(any())).thenAnswer(call -> ((ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection));
        var authorization = mock(BulkReadAuthorizationProvider.class);
        when(authorization.executionInfrastructure()).thenReturn(infrastructure);
        when(authorization.confirmationOperationId()).thenReturn(operation);
        when(authorization.preAuthorize(any(),any())).thenReturn(BulkReadAuthorizationProvider.GlobalDecision.DENIED);
        var context = new BulkFingerprintContext("namespace-a","denied-subject","p3.items",
                new CanonicalOperationRef("p3",operation,"/p3/items/bulk/update","POST"),"schema-a",ActionCollectionAtomicity.PER_ITEM);
        var policy = mock(BulkResourcePolicy.class);
        when(policy.context(operation)).thenReturn(context);
        when(policy.readAuthorization(operation)).thenReturn(authorization);
        var engine = new JpaResourceBulkEngine<Long,BulkDeclarativeJpaHttpTest.Filter>(infrastructure,
                mock(BulkOperationLifecycle.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),
                mock(BulkDomainResourceAdapter.class),BulkIdentityCodecs.longs(),
                mock(BulkEditableFields.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),new ObjectMapper(),policy,
                mock(BulkReadCursorConfiguration.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)),Map.of());
        var controller = new BulkDeclarativeJpaHttpTest.ItemController(); controller.engine = engine;
        ReflectionTestUtils.setField(controller,"bulkStatusLookup",new BulkOperationStatusLookup(infrastructure,discovery));
        int known = assertThrows(ResponseStatusException.class, () -> controller.bulkExecution(UUID.randomUUID())).getStatusCode().value();
        exists.set(false);
        int absent = assertThrows(ResponseStatusException.class, () -> controller.bulkExecution(UUID.randomUUID())).getStatusCode().value();
        verify(authorization,times(2)).preAuthorize(any(),any());
        verify(connection,never()).prepareStatement(anyString());
        assertEquals(known,absent,"globally denied requester sees known="+known+", absent="+absent);
    }
}
