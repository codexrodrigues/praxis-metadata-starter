package org.praxisplatform.uischema.id;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson module that serializes any {@link jakarta.persistence.Embeddable} or
 * {@link CompositeResourceId} as a canonical wire string and deserializes it from either a
 * canonical string ({@code "1001:1"}) or a JSON object ({@code {"numfunc":1001,"numvinc":1}}).
 */
public final class CompositeIdJacksonModule extends SimpleModule {

    public CompositeIdJacksonModule() {
        super("PraxisCompositeIdModule");

        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(
                    SerializationConfig config,
                    BeanDescription beanDesc,
                    JsonSerializer<?> serializer
            ) {
                if (CompositeIdSupport.isCompositeIdType(beanDesc.getBeanClass())) {
                    return new CompositeIdJsonSerializer();
                }
                return serializer;
            }

            @Override
            public JsonSerializer<?> modifyKeySerializer(
                    SerializationConfig config,
                    JavaType valueType,
                    BeanDescription beanDesc,
                    JsonSerializer<?> serializer
            ) {
                if (CompositeIdSupport.isCompositeIdType(valueType.getRawClass())) {
                    return new CompositeIdKeySerializer();
                }
                return serializer;
            }
        });

        setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(
                    DeserializationConfig config,
                    BeanDescription beanDesc,
                    JsonDeserializer<?> deserializer
            ) {
                Class<?> rawClass = beanDesc.getBeanClass();
                if (CompositeIdSupport.isCompositeIdType(rawClass)) {
                    return new CompositeIdJsonDeserializer<>(rawClass);
                }
                return deserializer;
            }
        });
    }

    private static final class CompositeIdJsonSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(CompositeIdSupport.encode(value));
        }
    }

    private static final class CompositeIdKeySerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeFieldName(CompositeIdSupport.encode(value));
        }
    }

    private static final class CompositeIdJsonDeserializer<T> extends JsonDeserializer<T> {
        private final Class<T> targetType;

        private CompositeIdJsonDeserializer(Class<T> targetType) {
            this.targetType = targetType;
        }

        @Override
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NUMBER_INT) {
                try {
                    return CompositeIdSupport.decode(p.getValueAsString(), targetType);
                } catch (IllegalArgumentException ex) {
                    throw ctxt.weirdStringException(p.getValueAsString(), targetType, ex.getMessage());
                }
            }
            if (token == JsonToken.START_OBJECT) {
                JsonNode node = p.getCodec().readTree(p);
                List<CompositeIdSupport.ComponentDescriptor> descriptors = CompositeIdSupport.descriptors(targetType);
                List<Object> values = new ArrayList<>(descriptors.size());
                try {
                    for (CompositeIdSupport.ComponentDescriptor descriptor : descriptors) {
                        JsonNode fieldNode = node.get(descriptor.name());
                        if (fieldNode == null || fieldNode.isNull()) {
                            values.add(null);
                        } else {
                            values.add(CompositeIdSupport.coerceScalar(fieldNode.asText(), descriptor.type()));
                        }
                    }
                    return CompositeIdSupport.fromComponentValues(targetType, values);
                } catch (IllegalArgumentException ex) {
                    throw ctxt.weirdStringException(node.toString(), targetType, ex.getMessage());
                }
            }
            @SuppressWarnings("unchecked")
            T handled = (T) ctxt.handleUnexpectedToken(targetType, p);
            return handled;
        }
    }
}
