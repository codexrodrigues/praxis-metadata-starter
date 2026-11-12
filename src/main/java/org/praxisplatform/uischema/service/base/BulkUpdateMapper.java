package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.math.BigDecimal;

/** Binds a persisted response projection and an untrusted bulk plan to the same update DTO. */
final class BulkUpdateMapper {
    private BulkUpdateMapper() { }

    static ObjectMapper strictCopy(ObjectMapper source) {
        return copy(source, Input.UPDATE_PLAN).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    static ObjectMapper projectionCopy(ObjectMapper source) {
        // toBulkUpdate projects response fields through a tree before binding the update DTO.
        return copy(source, Input.PERSISTED_RESPONSE)
                .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    }

    private enum Input { PERSISTED_RESPONSE, UPDATE_PLAN }

    private static ObjectMapper copy(ObjectMapper source, Input input) {
        // convertValue reads a TokenBuffer. Its parser may have different limits from the host factory.
        int maxLength = source.getFactory().streamReadConstraints().getMaxNumberLength();
        var module = new SimpleModule("PraxisBulkDeclaredDecimalText");
        module.setDeserializerModifier(new BeanDeserializerModifier() {
            @Override public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                    BeanDescription bean, JsonDeserializer<?> deserializer) {
                // A host's custom decimal reader remains its authority, including globally registered readers.
                return bean.getBeanClass() == BigDecimal.class
                        && deserializer.getClass() == NumberDeserializers.BigDecimalDeserializer.class
                        ? new DeclaredDecimal(maxLength, input) : deserializer;
            }
        });
        return source.copy().registerModule(module);
    }

    private static final class DeclaredDecimal extends NumberDeserializers.BigDecimalDeserializer implements ContextualDeserializer {
        private final int maxLength;
        private final Input input;
        private DeclaredDecimal(int maxLength, Input input) {
            this.maxLength = maxLength;
            this.input = input;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            if (property != null && property.getType().hasRawClass(BigDecimal.class) && property.getMember() != null) {
                var format = context.getAnnotationIntrospector().findFormat(property.getMember());
                if (format != null && format.getShape() == JsonFormat.Shape.STRING) return this;
            }
            return NumberDeserializers.BigDecimalDeserializer.instance;
        }

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // The server's response DTO can expose a native number even when its update DTO writes text.
            if (input == Input.PERSISTED_RESPONSE && parser.currentToken().isNumeric()) {
                return super.deserialize(parser, context);
            }
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(BigDecimal.class, "Expected a decimal encoded as a JSON string");
            }
            String value = parser.getText();
            if (value.length() > maxLength) {
                return context.reportInputMismatch(BigDecimal.class, "Decimal text exceeds the configured number length");
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException invalid) {
                throw context.weirdStringException(value, BigDecimal.class, "Invalid decimal text");
            }
        }
    }
}
