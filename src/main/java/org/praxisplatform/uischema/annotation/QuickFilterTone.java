package org.praxisplatform.uischema.annotation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tom visual e semantico unificado associado a {@link QuickFilter}, {@link WorkflowAction},
 * {@link UiOption}, {@link org.praxisplatform.uischema.fact.ResourceFact} e {@link Threshold}
 * para renderizacao consistente em {@code <praxis-scope-bar>}, toolbars, cartoes e catalogos executivos.
 */
public enum QuickFilterTone {
    UNSPECIFIED(""),
    NEUTRAL("neutral"),
    PRIMARY("primary"),
    SECONDARY("secondary"),
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    DANGER("danger"),
    CRITICAL("critical"),
    POSITIVE("positive"),
    NEGATIVE("negative");

    private static final Set<String> ALLOWED_WIRE_VALUES = buildAllowedWireValues();

    private static Set<String> buildAllowedWireValues() {
        Set<String> allowed = new LinkedHashSet<>();
        for (QuickFilterTone tone : values()) {
            if (!tone.wireValue.isEmpty()) {
                allowed.add(tone.wireValue);
            }
        }
        return Collections.unmodifiableSet(allowed);
    }

    private final String wireValue;

    QuickFilterTone(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Retorna o token serializado no contrato {@code x-ui.resource.quickFilters} e {@code /schemas/domain},
     * ou string vazia quando {@link #UNSPECIFIED}.
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Retorna o conjunto imutavel dos 10 tokens semanticos canonicos de {@code tone}
     * (excluindo {@link #UNSPECIFIED}).
     */
    public static Set<String> allowedWireValues() {
        return ALLOWED_WIRE_VALUES;
    }

    /**
     * Verifica se o valor informado corresponde a um dos tokens canonicos de {@link #allowedWireValues()}.
     */
    public static boolean isValidWireValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && ALLOWED_WIRE_VALUES.contains(normalized);
    }
}
