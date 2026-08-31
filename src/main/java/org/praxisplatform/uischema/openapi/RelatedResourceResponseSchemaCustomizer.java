package org.praxisplatform.uischema.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.ResolvableType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Map;

/**
 * Registers domain schemas used by concrete {@link RestApiResource} response types.
 *
 * <p>Jackson flattens {@code RestApiResource.content}, but swagger-core can otherwise publish a
 * related projection component containing only {@code _links}. This customizer reads the real
 * generic return type from the mapped handler, registers the DTO through {@link ModelConverters}
 * and rebuilds the resource component as {@code DTO + _links}. No class is inferred from a schema
 * name.</p>
 */
public final class RelatedResourceResponseSchemaCustomizer implements GlobalOpenApiCustomizer {

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    private final RequestMappingHandlerMapping handlerMapping;
    private final CanonicalOperationResolver canonicalOperationResolver;

    public RelatedResourceResponseSchemaCustomizer(
            RequestMappingHandlerMapping handlerMapping,
            CanonicalOperationResolver canonicalOperationResolver
    ) {
        this.handlerMapping = handlerMapping;
        this.canonicalOperationResolver = canonicalOperationResolver;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getPaths() == null) {
            return;
        }
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        if (components.getSchemas() == null) {
            components.setSchemas(new java.util.LinkedHashMap<>());
        }

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Class<?> dtoClass = findRestApiResourceContentClass(entry.getValue().getMethod().getGenericReturnType());
            if (dtoClass == null) {
                continue;
            }
            CanonicalOperationRef operation = canonicalOperationResolver.resolve(entry.getValue(), entry.getKey());
            if (operation == null || operation.path() == null || !openApi.getPaths().containsKey(operation.path())) {
                continue;
            }
            registerFlattenedResourceSchema(components.getSchemas(), dtoClass);
        }
    }

    private void registerFlattenedResourceSchema(Map<String, Schema> schemas, Class<?> dtoClass) {
        ResolvedSchema dtoResolution = ModelConverters.getInstance().resolveAsResolvedSchema(
                new AnnotatedType(dtoClass).resolveAsRef(true)
        );
        dtoResolution.referencedSchemas.forEach(schemas::putIfAbsent);
        String dtoSchemaName = schemaName(dtoResolution.schema);
        if (dtoSchemaName == null) {
            return;
        }

        Type resourceType = ResolvableType.forClassWithGenerics(RestApiResource.class, dtoClass).getType();
        ResolvedSchema resourceResolution = ModelConverters.getInstance().resolveAsResolvedSchema(
                new AnnotatedType(resourceType).resolveAsRef(true)
        );
        resourceResolution.referencedSchemas.forEach(schemas::putIfAbsent);
        String resourceSchemaName = schemaName(resourceResolution.schema);
        if (resourceSchemaName == null) {
            return;
        }

        ComposedSchema flattenedResource = new ComposedSchema();
        flattenedResource.addAllOfItem(refSchema(dtoSchemaName));
        ObjectSchema discovery = new ObjectSchema();
        discovery.addProperty("_links", refSchema("RestApiLinks"));
        flattenedResource.addAllOfItem(discovery);
        schemas.put(resourceSchemaName, flattenedResource);
    }

    private Class<?> findRestApiResourceContentClass(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> rawClass = rawClass(parameterizedType.getRawType());
            Type[] arguments = parameterizedType.getActualTypeArguments();
            if (rawClass == RestApiResource.class && arguments.length == 1) {
                return concreteClass(arguments[0]);
            }
            for (Type argument : arguments) {
                Class<?> nested = findRestApiResourceContentClass(argument);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return findRestApiResourceContentClass(genericArrayType.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type upperBound : wildcardType.getUpperBounds()) {
                Class<?> nested = findRestApiResourceContentClass(upperBound);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private Class<?> concreteClass(Type type) {
        if (type instanceof Class<?> concreteClass) {
            return concreteClass;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return rawClass(parameterizedType.getRawType());
        }
        return null;
    }

    private Class<?> rawClass(Type type) {
        return type instanceof Class<?> rawClass ? rawClass : null;
    }

    private String schemaName(Schema<?> schema) {
        if (schema == null || schema.get$ref() == null || !schema.get$ref().startsWith(SCHEMA_REF_PREFIX)) {
            return null;
        }
        return schema.get$ref().substring(SCHEMA_REF_PREFIX.length());
    }

    private Schema<?> refSchema(String schemaName) {
        return new Schema<>().$ref(SCHEMA_REF_PREFIX + schemaName);
    }
}
