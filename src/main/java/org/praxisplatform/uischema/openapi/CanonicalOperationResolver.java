package org.praxisplatform.uischema.openapi;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.Optional;

public interface CanonicalOperationResolver {

    String resolveGroup(String path);

    CanonicalOperationRef resolve(String path, String method);

    CanonicalOperationRef resolve(HandlerMethod handlerMethod, RequestMappingInfo mappingInfo);

    Optional<CanonicalOperationRef> resolveByOperationId(String operationId);
}
