package org.praxisplatform.uischema.bulk;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/** Materializes the declared bulk lifecycle identity on the corresponding real OpenAPI operation. */
public final class BulkResourceOperationIdCustomizer implements GlobalOperationCustomizer {

    private final BulkResourceOperationBindings bindings;

    public BulkResourceOperationIdCustomizer(BulkResourceOperationBindings bindings) {
        this.bindings = bindings;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        bindings.operationIdFor(handlerMethod).ifPresent(operation::setOperationId);
        return operation;
    }
}
