package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

/** Factory methods for the initially supported bulk-operation identity codecs. */
public final class BulkIdentityCodecs {

    private static final Pattern CANONICAL_LONG = Pattern.compile("^(?:0|[1-9][0-9]*|-[1-9][0-9]*)$");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );
    private static final String LONG_WIRE_DESCRIPTION = "Canonical decimal string in the inclusive range "
            + "-9223372036854775808 to 9223372036854775807; validated by this codec.";

    private BulkIdentityCodecs() {
    }

    public static BulkIdentityCodec<Integer, Integer> integers() {
        return IntegerCodec.INSTANCE;
    }

    public static BulkIdentityCodec<String, Long> longs() {
        return LongCodec.INSTANCE;
    }

    public static BulkIdentityCodec<String, String> strings() {
        return StringCodec.INSTANCE;
    }

    public static BulkIdentityCodec<String, UUID> uuids() {
        return UuidCodec.INSTANCE;
    }

    private enum IntegerCodec implements BulkIdentityCodec<Integer, Integer> {
        INSTANCE;

        @Override
        public Integer readWire(JsonNode node) {
            if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
                throw invalid("an int32 JSON integer");
            }
            return node.intValue();
        }

        @Override
        public Integer decode(Integer wire) {
            if (wire == null) {
                throw invalid("an int32 identity");
            }
            return wire;
        }

        @Override
        public Integer encode(Integer id) {
            if (id == null) {
                throw invalid("an int32 identity");
            }
            return id;
        }

        @Override
        public Schema<?> wireSchema() {
            return new IntegerSchema()
                    .format("int32")
                    .minimum(BigDecimal.valueOf(Integer.MIN_VALUE))
                    .maximum(BigDecimal.valueOf(Integer.MAX_VALUE));
        }

        @Override
        public String codecId() {
            return "integer";
        }
    }

    private enum LongCodec implements BulkIdentityCodec<String, Long> {
        INSTANCE;

        @Override
        public String readWire(JsonNode node) {
            String wire = requiredText(node, "a canonical int64 decimal string");
            decode(wire);
            return wire;
        }

        @Override
        public Long decode(String wire) {
            if (wire == null || !CANONICAL_LONG.matcher(wire).matches()) {
                throw invalid("a canonical int64 decimal string");
            }
            try {
                return Long.parseLong(wire);
            } catch (NumberFormatException exception) {
                throw invalid("a canonical int64 decimal string");
            }
        }

        @Override
        public String encode(Long id) {
            if (id == null) {
                throw invalid("an int64 identity");
            }
            return Long.toString(id);
        }

        @Override
        public Schema<?> wireSchema() {
            return new StringSchema()
                    .format("int64-decimal")
                    .pattern(CANONICAL_LONG.pattern())
                    .maxLength(20)
                    .description(LONG_WIRE_DESCRIPTION);
        }

        @Override
        public String codecId() {
            return "long";
        }
    }

    private enum StringCodec implements BulkIdentityCodec<String, String> {
        INSTANCE;

        @Override
        public String readWire(JsonNode node) {
            return requiredText(node, "a non-empty string");
        }

        @Override
        public String decode(String wire) {
            if (wire == null || wire.isEmpty()) {
                throw invalid("a non-empty string");
            }
            return wire;
        }

        @Override
        public String encode(String id) {
            if (id == null || id.isEmpty()) {
                throw invalid("a non-empty string");
            }
            return id;
        }

        @Override
        public Schema<?> wireSchema() {
            return new StringSchema().minLength(1);
        }

        @Override
        public String codecId() {
            return "string";
        }
    }

    private enum UuidCodec implements BulkIdentityCodec<String, UUID> {
        INSTANCE;

        @Override
        public String readWire(JsonNode node) {
            String wire = requiredText(node, "a canonical UUID string");
            decode(wire);
            return wire;
        }

        @Override
        public UUID decode(String wire) {
            if (wire == null || !CANONICAL_UUID.matcher(wire).matches()) {
                throw invalid("a canonical UUID string");
            }
            try {
                return UUID.fromString(wire);
            } catch (IllegalArgumentException exception) {
                throw invalid("a canonical UUID string");
            }
        }

        @Override
        public String encode(UUID id) {
            if (id == null) {
                throw invalid("a UUID identity");
            }
            return id.toString();
        }

        @Override
        public Schema<?> wireSchema() {
            return new StringSchema()
                    .format("uuid")
                    .pattern(CANONICAL_UUID.pattern())
                    .minLength(36)
                    .maxLength(36);
        }

        @Override
        public String codecId() {
            return "uuid";
        }
    }

    private static String requiredText(JsonNode node, String expected) {
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) {
            throw invalid(expected);
        }
        return node.textValue();
    }

    private static IllegalArgumentException invalid(String expected) {
        return new IllegalArgumentException("Bulk identity must be " + expected + ".");
    }
}
