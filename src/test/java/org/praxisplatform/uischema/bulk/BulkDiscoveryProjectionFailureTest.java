package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BulkDiscoveryProjectionFailureTest {
    static RuntimeException failedProjection(RuntimeException infrastructureFailure) throws Exception {
        var outer=mock(BulkOperationLifecycle.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var type=Arrays.stream(BulkOperationLifecycle.class.getDeclaredClasses())
            .filter(c->c.getSimpleName().equals("ResponseProjectionFrame")).findFirst().orElseThrow();
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var fence=new OpenApiDocumentService.BulkLifecycleDocumentFence() {
            public <T> T read(Supplier<T> verification) { throw infrastructureFailure; }
            public void close() {}
        };
        var frame=constructor.newInstance(outer,"sample.records",Map.of(),List.of(),Map.of(),fence);
        var verify=type.getDeclaredMethod("verifyFinal");verify.setAccessible(true);
        try { verify.invoke(frame);throw new AssertionError("Projection must fail closed"); }
        catch(InvocationTargetException failure) { return (RuntimeException)failure.getCause(); }
    }
    @RestController static class Endpoint {
        RuntimeException failure;
        @GetMapping("/proof/capabilities") Object read() { throw failure; }
    }
    @Test void lostFinalProofBecomesSafe503RatherThanPartialResponseOrInfrastructure500() throws Exception {
        for (var cause:List.of(new TransactionTimedOutException("private SQL credentials"),
            new org.springframework.dao.DataAccessResourceFailureException("private connection"),
            new IllegalStateException("publication changed"))) {
            var endpoint=new Endpoint();endpoint.failure=failedProjection(cause);
            assertThat(endpoint.failure).isInstanceOf(BulkDiscoveryUnavailableException.class).hasNoCause();
            var response=MockMvcBuilders.standaloneSetup(endpoint).setControllerAdvice(new GlobalExceptionHandler())
                .build().perform(get("/proof/capabilities"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
            assertThat(response).contains("BULK_DISCOVERY_UNAVAILABLE").doesNotContain("private", "credentials", "publication changed");
        }
    }

    @Test void timeoutBeforeProjectionRequiresStateInspectionAndNeverLeaksCause() throws Exception {
        var endpoint=new Endpoint(); endpoint.failure=new TransactionTimedOutException("private JDBC deadline");
        var response=MockMvcBuilders.standaloneSetup(endpoint).setControllerAdvice(new GlobalExceptionHandler())
            .build().perform(get("/proof/capabilities")).andExpect(status().isServiceUnavailable())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("TRANSACTION_TIMEOUT", "Check its current state").doesNotContain("private", "JDBC");
    }
}
