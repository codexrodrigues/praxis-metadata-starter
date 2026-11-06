package org.praxisplatform.uischema.service.base;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BulkUpdateMapperTest {
    record Input(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal exact,
                 BigDecimal ordinary, Long id, Boolean enabled) { }

    @Test void explicitDecimalTextRoundTripsWithoutRoundingOrChangingTheCallerMapper() throws Exception {
        var source = new ObjectMapper(); var strict = BulkUpdateMapper.strictCopy(source);
        var decimal = new BigDecimal("123456789012345678901234567890.1234567800");
        var plan = source.valueToTree(new Input(decimal, new BigDecimal("0.125"), 9007199254740993L, false));
        assertThat(plan.path("exact").isTextual()).isTrue();
        var result = strict.convertValue(plan, Input.class);
        org.junit.jupiter.api.Assertions.assertEquals(decimal, result.exact());
        assertThat(result.ordinary()).isEqualByComparingTo("0.125");
        assertThat(result.id()).isEqualTo(9007199254740993L); assertThat(result.enabled()).isFalse();
        assertThat(source.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS)).isTrue();
    }
    @Test void ordinaryNumbersIdsAndBooleansKeepStrictTypeChecks() {
        var strict = BulkUpdateMapper.strictCopy(new ObjectMapper());
        for (String payload : new String[]{"{\"ordinary\":\"1.25\"}","{\"id\":\"7\"}","{\"enabled\":\"false\"}"})
            assertThatThrownBy(()->strict.readValue(payload,Input.class)).isInstanceOf(JsonMappingException.class);
    }
    @Test void explicitNullZeroNegativeAndExponentRemainDistinct() throws Exception {
        var strict = BulkUpdateMapper.strictCopy(new ObjectMapper());
        assertThat(strict.readValue("{\"exact\":null}",Input.class).exact()).isNull();
        assertThat(strict.readValue("{\"exact\":\"0\"}",Input.class).exact()).isEqualByComparingTo("0");
        assertThat(strict.readValue("{\"exact\":\"-0.125\"}",Input.class).exact()).isEqualByComparingTo("-0.125");
        assertThat(strict.readValue("{\"exact\":\"1E-30\"}",Input.class).exact()).isEqualByComparingTo("1E-30");
        assertThat(strict.readValue("{}",Input.class).exact()).isNull();
    }
    @Test void malformedBlankBooleanAndOversizedDecimalTextAreRejected() {
        var strict = BulkUpdateMapper.strictCopy(new ObjectMapper());
        for (String value : new String[]{"NaN",""," ","1,25","1".repeat(1001)})
            assertThatThrownBy(()->strict.readValue("{\"exact\":\""+value+"\"}",Input.class)).isInstanceOf(JsonMappingException.class);
        for (String token : new String[]{"5", "1.25", "true", "{}", "[]"}) {
            assertThatThrownBy(() -> strict.readValue("{\"exact\":" + token + "}", Input.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
        }
    }

    @Test void convertValueUsesTheConfiguredDecimalLengthIncludingItsBoundary() {
        var mapper = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNumberLength(4).build())
                .build());
        var strict = BulkUpdateMapper.strictCopy(mapper);
        var plan = mapper.createObjectNode().put("exact", "1.20");
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("1.20"), strict.convertValue(plan, Input.class).exact());
        plan.put("exact", "1.200");
        assertThatThrownBy(() -> strict.convertValue(plan, Input.class))
                .hasCauseInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }
    record Custom(@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=CustomDecimal.class) BigDecimal value) { }

    record EmptyDecimal(@JsonFormat(shape=JsonFormat.Shape.STRING)
                        @com.fasterxml.jackson.annotation.JsonSetter(nulls=com.fasterxml.jackson.annotation.Nulls.AS_EMPTY)
                        BigDecimal value) { }

    @Test void explicitNullPolicyKeepsTheStandardDecimalEmptyValue() throws Exception {
        var strict = BulkUpdateMapper.strictCopy(new ObjectMapper());
        assertThat(strict.readValue("{\"value\":null}", EmptyDecimal.class).value()).isEqualByComparingTo("0");
    }
    static final class CustomDecimal extends JsonDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if (!"special".equals(parser.getText())) throw JsonMappingException.from(parser,"custom contract");
            return new BigDecimal("42");
        }
    }
    @Test void customPropertyDeserializerKeepsOwnership() throws Exception {
        assertThat(BulkUpdateMapper.strictCopy(new ObjectMapper()).readValue("{\"value\":\"special\"}",Custom.class).value()).isEqualByComparingTo("42");
    }
    @Test void globalCustomDecimalDeserializerKeepsOwnership() throws Exception {
        var source = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                .addDeserializer(BigDecimal.class,new CustomDecimal()));
        assertThat(BulkUpdateMapper.strictCopy(source).readValue("{\"exact\":\"special\"}",Input.class).exact()).isEqualByComparingTo("42");
        assertThat(BulkUpdateMapper.projectionCopy(source).readValue("{\"exact\":\"special\"}",Input.class).exact()).isEqualByComparingTo("42");
    }

    @Test void persistedProjectionKeepsHostCoercionSettingsForUnrelatedProperties() throws Exception {
        var host = new ObjectMapper();
        assertThat(BulkUpdateMapper.projectionCopy(host).readValue("{\"id\":\"7\"}",Input.class).id()).isEqualTo(7L);
        host.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        assertThatThrownBy(() -> BulkUpdateMapper.projectionCopy(host).readValue("{\"id\":\"7\"}",Input.class))
                .isInstanceOf(JsonMappingException.class);
        assertThat(BulkUpdateMapper.projectionCopy(host).readValue("{\"value\":\"special\"}",Custom.class).value()).isEqualByComparingTo("42");
    }
}
