package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodPayloadNormalizer;
import org.praxisplatform.uischema.filter.range.RangePayloadNormalizer;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.praxisplatform.uischema.filter.web.FilterRequestBodyAdvice;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiUiSchemaAutoConfigurationFilterPayloadNormalizerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test
    void shouldWireOrderedPayloadNormalizersIntoFilterRequestBodyAdvice() {
        contextRunner.run(context -> {
            FilterRequestBodyAdvice advice = context.getBean(FilterRequestBodyAdvice.class);
            FilterPayloadNormalizer range = context.getBean("rangePayloadNormalizer", FilterPayloadNormalizer.class);
            FilterPayloadNormalizer relativePeriod = context.getBean("relativePeriodPayloadNormalizer", FilterPayloadNormalizer.class);

            assertNotNull(advice);
            assertInstanceOf(RangePayloadNormalizer.class, range);
            assertInstanceOf(RelativePeriodPayloadNormalizer.class, relativePeriod);

            @SuppressWarnings("unchecked")
            List<FilterPayloadNormalizer> adviceNormalizers =
                    (List<FilterPayloadNormalizer>) ReflectionTestUtils.getField(advice, "payloadNormalizers");

            assertNotNull(adviceNormalizers);
            assertEquals(2, adviceNormalizers.size());
            assertIterableEquals(List.of(range, relativePeriod), adviceNormalizers);
        });
    }
}
