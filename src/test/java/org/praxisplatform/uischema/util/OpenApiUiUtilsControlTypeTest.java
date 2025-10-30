package org.praxisplatform.uischema.util;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiUiUtilsControlTypeTest {

    @Test
    void nomeCompletoWithMax200ShouldBeInput() {
        String control = OpenApiUiUtils.determineEffectiveControlType(
                "string", null, false, 200,
                false, null, null, false,
                "nomeCompleto"
        );
        assertEquals(FieldControlType.INPUT.getValue(), control);
    }

    @Test
    void descricaoWithMax1000ShouldBeTextarea() {
        String control = OpenApiUiUtils.determineEffectiveControlType(
                "string", null, false, 1000,
                false, null, null, false,
                "descricao"
        );
        assertEquals(FieldControlType.TEXTAREA.getValue(), control);
    }

    @Test
    void emailFormatShouldRemainEmailInput() {
        String control = OpenApiUiUtils.determineEffectiveControlType(
                "string", "email", false, 50,
                false, null, null, false,
                "email"
        );
        // Smart detection also returns EMAIL_INPUT; test ensures not regressing format mapping
        assertEquals(FieldControlType.EMAIL_INPUT.getValue(), control);
    }
}

