package org.praxisplatform.uischema.openapi;

import java.io.IOException;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Gives Springdoc's ordered JSON a stable order by the actual wire keys. */
@ControllerAdvice(assignableTypes = {OpenApiResource.class, MultipleOpenApiWebMvcResource.class})
public final class OrderedOpenApiJsonResponse implements ResponseBodyAdvice<byte[]> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return ByteArrayHttpMessageConverter.class.isAssignableFrom(converterType)
                && "openapiJson".equals(returnType.getExecutable().getName());
    }

    @Override
    public byte[] beforeBodyWrite(byte[] body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null) return null;
        try {
            return OpenApiJsonMemberOrder.sort(body);
        } catch (IOException invalidDocument) {
            throw new IllegalStateException("Unable to serialize the ordered OpenAPI document", invalidDocument);
        }
    }
}
