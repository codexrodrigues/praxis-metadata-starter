package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkOperationDescriptorProvider;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Conditional wiring for the explicitly opted-in governed bulk lifecycle. */
@AutoConfiguration
@AutoConfigureAfter(OpenApiUiSchemaAutoConfiguration.class)
@ConditionalOnBean({BulkControlPlaneInfrastructure.class, BulkExecutionInfrastructure.class,
        RequestMappingHandlerMapping.class, CanonicalOperationResolver.class, OpenApiDocumentService.class,
        ActionDefinitionRegistry.class, SchemaReferenceResolver.class, BulkResourceOperationBindings.class})
public class BulkOperationLifecycleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BulkOperationLifecycle.class)
    public BulkOperationLifecycle bulkOperationLifecycle(BulkControlPlaneInfrastructure controlPlane,
            BulkExecutionInfrastructure runtime, BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operations,
            OpenApiDocumentService documents, ActionDefinitionRegistry actions,
            ObjectMapper objectMapper, SchemaReferenceResolver schemaReferences,
            ObjectProvider<BulkOperationDescriptorProvider> providers) {
        return new BulkOperationLifecycle(bindings, operations, documents, actions,
                objectMapper.getTypeFactory(), schemaReferences, runtime, controlPlane,
                providers.orderedStream().toList());
    }
}
