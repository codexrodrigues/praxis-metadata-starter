package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.springframework.core.ResolvableType;
import org.springframework.http.*;
import org.springframework.http.converter.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

/** Strict bounded bulk ingress, registered ahead of ordinary Jackson conversion. */
public final class PraxisBulkProtocolHttpMessageConverter extends AbstractGenericHttpMessageConverter<Object> {
    private final ObjectMapper mapper;
    private final List<FilterPayloadNormalizer> normalizers;
    private final BulkProtocolLimits limits;

    public PraxisBulkProtocolHttpMessageConverter(ObjectMapper mapper, List<FilterPayloadNormalizer> normalizers) {
        this(mapper, normalizers, BulkProtocolLimits.defaults());
    }

    public PraxisBulkProtocolHttpMessageConverter(ObjectMapper mapper, List<FilterPayloadNormalizer> normalizers,
                                                  BulkProtocolLimits limits) {
        super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.normalizers = List.copyOf(normalizers);
        this.limits = java.util.Objects.requireNonNull(limits);
    }

    @Override protected boolean supports(Class<?> type) {
        return type == BulkUniformEvaluationRequest.class || type == BulkItemEvaluationRequest.class
                || type == BulkCommandEvaluationRequest.class || type == BulkConfirmationRequest.class;
    }

    @Override public boolean canRead(Type type, Class<?> context, MediaType media) {
        return supports(ResolvableType.forType(type).toClass()) && canRead(media);
    }

    @Override public boolean canWrite(Type type, Class<?> clazz, MediaType media) { return false; }
    @Override public boolean canWrite(Class<?> clazz, MediaType media) { return false; }

    @Override public Object read(Type type, Class<?> context, HttpInputMessage input) throws IOException {
        try {
            byte[] body = input.getBody().readNBytes(limits.maxRequestBytes() + 1);
            var resolved = ResolvableType.forType(context == null ? type
                    : org.springframework.core.GenericTypeResolver.resolveType(type, context));
            Class<?> raw = resolved.toClass();
            if (raw == BulkConfirmationRequest.class)
                return new BulkProtocolReader<>(BulkIdentityCodecs.strings(), limits).readConfirmation(body);
            if (context == null) throw new IllegalArgumentException("Bulk resource controller context is required");
            var codec = BulkResourceTypes.identityCodec(BulkResourceTypes.identityType(context));
            var reader = new BulkProtocolReader<>(codec, limits);
            if (raw == BulkItemEvaluationRequest.class) return reader.readItems(body);
            if (raw == BulkUniformEvaluationRequest.class)
                return reader.readUniform(body, node -> bind(node, resolved.getGeneric(1), true));
            if (raw == BulkCommandEvaluationRequest.class)
                return reader.readCommand(body, node -> bind(node, resolved.getGeneric(0), false),
                        node -> bind(node, resolved.getGeneric(2), true));
            throw new IllegalArgumentException("Unsupported bulk protocol request");
        } catch (IllegalArgumentException ex) {
            // Do not attach a Jackson cause or protected request contents to public diagnostics.
            throw new HttpMessageNotReadableException("Invalid bulk protocol request", input);
        }
    }

    private Object bind(JsonNode value, ResolvableType type, boolean filter) {
        if (type.resolve() == null || type.resolve() == Object.class)
            throw new IllegalArgumentException("Bulk domain types must be concrete");
        var node = (ObjectNode) value.deepCopy();
        if (filter) normalizers.forEach(normalizer -> normalizer.normalizeInPlace(node, type.toClass()));
        return mapper.convertValue(node, mapper.constructType(type.getType()));
    }

    @Override protected Object readInternal(Class<?> clazz, HttpInputMessage input) throws IOException {
        return read(clazz, null, input);
    }
    @Override protected void writeInternal(Object value, Type type, HttpOutputMessage output) {
        throw new UnsupportedOperationException("Bulk protocol converter only reads requests");
    }
}
