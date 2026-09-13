package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkIdentityCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void integerCodecRequiresAnInt32IntegralJsonToken() throws Exception {
        BulkIdentityCodec<Integer, Integer> codec = BulkIdentityCodecs.integers();

        assertEquals(Integer.MIN_VALUE, codec.decode(codec.readWire(node("-2147483648"))));
        assertEquals(Integer.MAX_VALUE, codec.decode(codec.readWire(node("2147483647"))));
        assertEquals("integer", codec.codecId());

        assertRejected(() -> {
            codec.readWire(node("2147483648"));
        });
        assertRejected(() -> {
            codec.readWire(node("1.0"));
        });
        assertRejected(() -> {
            codec.readWire(node("\"1\""));
        });
        assertRejected(() -> {
            codec.readWire(node("null"));
        });
    }

    @Test
    void longCodecUsesCanonicalDecimalStringsWithoutPrecisionLoss() throws Exception {
        BulkIdentityCodec<String, Long> codec = BulkIdentityCodecs.longs();

        assertEquals(Long.MIN_VALUE, codec.decode(codec.readWire(node("\"-9223372036854775808\""))));
        assertEquals(Long.MAX_VALUE, codec.decode(codec.readWire(node("\"9223372036854775807\""))));
        assertEquals("9007199254740993", codec.encode(codec.decode(codec.readWire(node("\"9007199254740993\"")))));
        assertEquals("long", codec.codecId());

        assertRejected(() -> {
            codec.readWire(node("9007199254740993"));
        });
        assertRejected(() -> {
            codec.readWire(node("\"01\""));
        });
        assertRejected(() -> {
            codec.readWire(node("\"+1\""));
        });
        assertRejected(() -> {
            codec.readWire(node("\"-0\""));
        });
        assertRejected(() -> {
            codec.readWire(node("\" 1\""));
        });
        assertRejected(() -> {
            codec.readWire(node("\"9223372036854775808\""));
        });
    }

    @Test
    void stringCodecPreservesMeaningfulCharactersAndRejectsOnlyEmptyStrings() throws Exception {
        BulkIdentityCodec<String, String> codec = BulkIdentityCodecs.strings();
        String identity = " 00AbC ";

        assertEquals(identity, codec.decode(codec.readWire(node("\" 00AbC \""))));
        assertEquals(" ", codec.decode(codec.readWire(node("\" \""))));
        assertEquals("string", codec.codecId());

        assertRejected(() -> {
            codec.readWire(node("\"\""));
        });
        assertRejected(() -> {
            codec.readWire(node("1"));
        });
        assertRejected(() -> codec.decode(null));
    }

    @Test
    void uuidCodecRequiresCanonicalLowercaseWireStrings() throws Exception {
        BulkIdentityCodec<String, UUID> codec = BulkIdentityCodecs.uuids();
        UUID expected = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        assertEquals(expected, codec.decode(codec.readWire(node("\"123e4567-e89b-12d3-a456-426614174000\""))));
        assertEquals("123e4567-e89b-12d3-a456-426614174000", codec.encode(expected));
        assertEquals("uuid", codec.codecId());

        assertRejected(() -> {
            codec.readWire(node("\"123E4567-E89B-12D3-A456-426614174000\""));
        });
        assertRejected(() -> {
            codec.readWire(node("\"123e4567-e89b-12d3-a456-42661417400\""));
        });
        assertRejected(() -> {
            codec.readWire(node("123"));
        });
    }

    @Test
    void wireSchemasDescribeEachWireTypeAndAreFreshInstances() {
        Schema<?> integerSchema = BulkIdentityCodecs.integers().wireSchema();
        Schema<?> longSchema = BulkIdentityCodecs.longs().wireSchema();
        Schema<?> stringSchema = BulkIdentityCodecs.strings().wireSchema();
        Schema<?> uuidSchema = BulkIdentityCodecs.uuids().wireSchema();

        assertEquals("integer", integerSchema.getType());
        assertEquals("int32", integerSchema.getFormat());
        assertEquals(BigDecimal.valueOf(Integer.MIN_VALUE), integerSchema.getMinimum());
        assertEquals(BigDecimal.valueOf(Integer.MAX_VALUE), integerSchema.getMaximum());
        assertEquals("string", longSchema.getType());
        assertEquals("int64-decimal", longSchema.getFormat());
        assertEquals("^(?:0|[1-9][0-9]*|-[1-9][0-9]*)$", longSchema.getPattern());
        assertEquals(20, longSchema.getMaxLength());
        assertEquals(
                "Canonical decimal string in the inclusive range -9223372036854775808 to "
                        + "9223372036854775807; validated by this codec.",
                longSchema.getDescription()
        );
        assertEquals("string", stringSchema.getType());
        assertEquals(1, stringSchema.getMinLength());
        assertEquals("string", uuidSchema.getType());
        assertEquals("uuid", uuidSchema.getFormat());
        assertEquals(36, uuidSchema.getMinLength());
        assertEquals(36, uuidSchema.getMaxLength());

        Schema<?> anotherLongSchema = BulkIdentityCodecs.longs().wireSchema();
        assertNotSame(longSchema, anotherLongSchema);
        longSchema.setPattern("mutated");
        assertEquals("^(?:0|[1-9][0-9]*|-[1-9][0-9]*)$", anotherLongSchema.getPattern());
    }

    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }

    private void assertRejected(ThrowingRunnable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(exception.getMessage().startsWith("Bulk identity must be "));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
