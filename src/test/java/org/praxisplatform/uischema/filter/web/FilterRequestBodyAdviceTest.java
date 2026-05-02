package org.praxisplatform.uischema.filter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterRequestBodyAdviceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldApplyAllPayloadNormalizersBeforeDeserialization() throws Exception {
        FilterRequestBodyAdvice advice = new FilterRequestBodyAdvice(objectMapper);
        Method method = TestController.class.getDeclaredMethod("filter", CombinedFilterDTO.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        MockHttpInputMessage inputMessage = new MockHttpInputMessage("""
                {
                  "valor": { "minPrice": 6500, "maxPrice": 15000 },
                  "publicadoEmPreset": "last7"
                }
                """.getBytes());
        inputMessage.getHeaders().putAll(headers);

        HttpInputMessage result = advice.beforeBodyRead(
                inputMessage,
                parameter,
                CombinedFilterDTO.class,
                MappingJackson2HttpMessageConverter.class);

        JsonNode normalized = objectMapper.readTree(result.getBody());
        assertFalse(normalized.has("valor"));
        assertEquals(2, normalized.path("valorBetween").size());
        assertEquals("6500", normalized.path("valorBetween").get(0).asText());
        assertEquals("15000", normalized.path("valorBetween").get(1).asText());
        assertFalse(normalized.has("publicadoEmPreset"));
        assertEquals(7, normalized.path("publicadoEmLastDays").asInt());
    }

    @Test
    void shouldSupportGenericFilterDtoTargetsOnly() throws Exception {
        FilterRequestBodyAdvice advice = new FilterRequestBodyAdvice(objectMapper);
        Method method = TestController.class.getDeclaredMethod("plain", PlainPayload.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        boolean supported = advice.supports(
                parameter,
                PlainPayload.class,
                MappingJackson2HttpMessageConverter.class);

        assertFalse(supported);
    }

    @Test
    void shouldWrapLegacyOptionSourceFilterPayloadIntoCanonicalEnvelope() throws Exception {
        FilterRequestBodyAdvice advice = new FilterRequestBodyAdvice(objectMapper);
        Method method = TestController.class.getDeclaredMethod("optionSourceFilter", OptionSourceFilterRequest.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        MockHttpInputMessage inputMessage = new MockHttpInputMessage("""
                {
                  "valor": { "minPrice": 6500, "maxPrice": 15000 }
                }
                """.getBytes());
        inputMessage.getHeaders().putAll(headers);

        HttpInputMessage result = advice.beforeBodyRead(
                inputMessage,
                parameter,
                method.getGenericParameterTypes()[0],
                MappingJackson2HttpMessageConverter.class
        );

        JsonNode normalized = objectMapper.readTree(result.getBody());
        assertTrue(normalized.has("filter"));
        assertEquals("6500", normalized.path("filter").path("valorBetween").get(0).asText());
        assertEquals("15000", normalized.path("filter").path("valorBetween").get(1).asText());
    }

    @Test
    void shouldSupportCanonicalOptionSourceFilterEnvelopeTargets() throws Exception {
        FilterRequestBodyAdvice advice = new FilterRequestBodyAdvice(objectMapper);
        Method method = TestController.class.getDeclaredMethod("optionSourceFilter", OptionSourceFilterRequest.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        boolean supported = advice.supports(
                parameter,
                method.getGenericParameterTypes()[0],
                MappingJackson2HttpMessageConverter.class
        );

        assertTrue(supported);
    }

    @SuppressWarnings("unused")
    static class TestController {
        public void filter(CombinedFilterDTO dto) {
        }

        public void plain(PlainPayload dto) {
        }

        public void optionSourceFilter(OptionSourceFilterRequest<CombinedFilterDTO> dto) {
        }
    }

    static class CombinedFilterDTO implements GenericFilterDTO {
        @UISchema(numericFormat = NumericFormat.CURRENCY)
        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "valor")
        private List<BigDecimal> valorBetween;

        @UISchema(controlType = FieldControlType.INLINE_RELATIVE_PERIOD)
        private String publicadoEmPreset;

        @Filterable(operation = Filterable.FilterOperation.IN_LAST_DAYS, relation = "publicadoEm")
        private Integer publicadoEmLastDays;

        @Filterable(operation = Filterable.FilterOperation.ON_DATE, relation = "publicadoEm")
        private LocalDate publicadoEmOn;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "publicadoEm")
        private List<OffsetDateTime> publicadoEmBetween;
    }

    static class PlainPayload {
        private String value;
    }
}
