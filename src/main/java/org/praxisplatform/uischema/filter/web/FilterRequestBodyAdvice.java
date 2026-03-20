package org.praxisplatform.uischema.filter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodPayloadNormalizer;
import org.praxisplatform.uischema.filter.range.RangePayloadNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Normaliza payloads de filtros de range antes da desserialização de DTOs.
 */
@ControllerAdvice
public class FilterRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private final ObjectMapper objectMapper;
    private final List<FilterPayloadNormalizer> payloadNormalizers;

    @Autowired
    public FilterRequestBodyAdvice(ObjectMapper objectMapper) {
        this(objectMapper, false, true);
    }

    public FilterRequestBodyAdvice(
            ObjectMapper objectMapper,
            List<FilterPayloadNormalizer> payloadNormalizers
    ) {
        this.objectMapper = objectMapper;
        this.payloadNormalizers = List.copyOf(payloadNormalizers);
    }

    public FilterRequestBodyAdvice(
            ObjectMapper objectMapper,
            boolean allowScalarRangePayload,
            boolean logLegacyScalarRangePayload
    ) {
        this(
                objectMapper,
                List.of(
                new RangePayloadNormalizer(allowScalarRangePayload, logLegacyScalarRangePayload),
                new RelativePeriodPayloadNormalizer()
                )
        );
    }

    @Override
    public boolean supports(
            MethodParameter methodParameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> targetClass = resolveTargetClass(methodParameter, targetType);
        return targetClass != null
                && GenericFilterDTO.class.isAssignableFrom(targetClass)
                && org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(inputMessage.getBody());
        if (body.length == 0 || !isJson(inputMessage.getHeaders().getContentType())) {
            return new ByteArrayHttpInputMessage(body, inputMessage.getHeaders());
        }

        Class<?> targetClass = resolveTargetClass(parameter, targetType);
        if (targetClass == null) {
            return new ByteArrayHttpInputMessage(body, inputMessage.getHeaders());
        }

        try {
            JsonNode tree = objectMapper.readTree(body);
            if (tree instanceof ObjectNode objectNode) {
                boolean changed = false;
                for (FilterPayloadNormalizer normalizer : payloadNormalizers) {
                    changed = normalizer.normalizeInPlace(objectNode, targetClass) || changed;
                }
                if (changed) {
                    body = objectMapper.writeValueAsBytes(objectNode);
                }
            }
            return new ByteArrayHttpInputMessage(body, inputMessage.getHeaders());
        } catch (IOException e) {
            String msg = "Failed to process filter JSON payload.";
            throw new HttpMessageNotReadableException(msg, e, inputMessage);
        }
    }

    private boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return true;
        }
        if (MediaType.APPLICATION_JSON.includes(mediaType)) {
            return true;
        }
        String subtype = mediaType.getSubtype();
        return subtype != null && subtype.toLowerCase().endsWith("+json");
    }

    private Class<?> resolveTargetClass(MethodParameter parameter, Type targetType) {
        if (targetType instanceof Class<?> cls) {
            return cls;
        }
        if (targetType instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        Class<?> fallback = parameter != null ? parameter.getParameterType() : null;
        return fallback != null ? fallback : null;
    }

    private static final class ByteArrayHttpInputMessage implements HttpInputMessage {
        private final byte[] body;
        private final HttpHeaders headers;

        private ByteArrayHttpInputMessage(byte[] body, HttpHeaders originalHeaders) {
            this.body = body != null ? body : new byte[0];
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(originalHeaders);
            copy.setContentLength(this.body.length);
            this.headers = copy;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
