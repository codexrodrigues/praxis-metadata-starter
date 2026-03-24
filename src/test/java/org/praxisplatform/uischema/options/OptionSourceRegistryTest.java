package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionSourceRegistryTest {

    @Test
    void resolvesDescriptorByResourceAndKey() {
        OptionSourceDescriptor profile = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/human-resources/vw-analytics-folha-pagamento",
                "profileFilter",
                "payrollProfile",
                null,
                null,
                List.of("competenciaBetween"),
                OptionSourcePolicy.defaults()
        );

        OptionSourceRegistry registry = OptionSourceRegistry.builder()
                .add(PayrollView.class, profile)
                .build();

        assertTrue(registry.contains(PayrollView.class, "payrollProfile"));
        assertSame(profile, registry.resolve(PayrollView.class, "payrollProfile").orElseThrow());
        assertSame(profile, registry.resolveByResourcePathAndField("/api/human-resources/vw-analytics-folha-pagamento", "profileFilter").orElseThrow());
        assertFalse(registry.contains(PayrollView.class, "universo"));
        assertFalse(registry.resolve(null, "payrollProfile").isPresent());
    }

    @Test
    void emptyRegistryRemainsSafe() {
        OptionSourceRegistry registry = OptionSourceRegistry.empty();

        assertTrue(registry.isEmpty());
        assertFalse(registry.contains(PayrollView.class, "payrollProfile"));
    }

    @Test
    void mergesRegistriesWithoutLosingDescriptors() {
        OptionSourceDescriptor payrollProfile = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/human-resources/vw-analytics-folha-pagamento",
                "payrollProfile",
                "payrollProfile",
                "payrollProfile",
                "payrollProfile",
                List.of(),
                OptionSourcePolicy.defaults()
        );
        OptionSourceDescriptor universo = new OptionSourceDescriptor(
                "universo",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/human-resources/vw-perfil-heroi",
                "universo",
                "universo",
                "universo",
                "universo",
                List.of(),
                OptionSourcePolicy.defaults()
        );

        OptionSourceRegistry merged = OptionSourceRegistry.merge(
                OptionSourceRegistry.builder().add(PayrollView.class, payrollProfile).build(),
                OptionSourceRegistry.builder().add(HeroView.class, universo).build()
        );

        assertTrue(merged.contains(PayrollView.class, "payrollProfile"));
        assertTrue(merged.contains(HeroView.class, "universo"));
    }

    static final class PayrollView {
    }

    static final class HeroView {
    }
}
