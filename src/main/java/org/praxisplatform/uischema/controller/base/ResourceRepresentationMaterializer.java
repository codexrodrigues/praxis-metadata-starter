package org.praxisplatform.uischema.controller.base;

import org.praxisplatform.uischema.rest.response.RestApiResource;

import java.util.List;

/**
 * Materializes DTOs through the canonical resource controller identified by {@code resourceKey}.
 *
 * <p>This is the supported boundary for parent projections that return child resources. It keeps
 * identity, actions and discovery owned by the child resource and prevents hosts from rebuilding
 * HATEOAS links or availability rules.</p>
 */
public interface ResourceRepresentationMaterializer {

    <T> RestApiResource<T> materialize(String resourceKey, T dto);

    default <T> List<RestApiResource<T>> materializeAll(String resourceKey, List<T> dtos) {
        if (dtos == null) {
            throw new IllegalArgumentException("Resource representation DTO list must not be null");
        }
        return dtos.stream().map(dto -> materialize(resourceKey, dto)).toList();
    }
}
