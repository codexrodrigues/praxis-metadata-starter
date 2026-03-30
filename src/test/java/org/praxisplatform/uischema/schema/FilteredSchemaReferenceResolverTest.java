package org.praxisplatform.uischema.schema;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.id.SchemaIdBuilder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilteredSchemaReferenceResolverTest {

    private final FilteredSchemaReferenceResolver resolver = new FilteredSchemaReferenceResolver();

    @Test
    void resolveNormalizesPathMethodAndSchemaType() {
        Locale locale = Locale.forLanguageTag("pt-BR");

        CanonicalSchemaRef ref = resolver.resolve(
                "api/human-resources/employees/",
                "POST",
                "RESPONSE",
                true,
                "tenant-a",
                locale,
                "employeeId",
                true
        );

        assertEquals("response", ref.schemaType());
        assertEquals(
                "/schemas/filtered?path=%2Fapi%2Fhuman-resources%2Femployees&operation=post&schemaType=response&includeInternalSchemas=true&idField=employeeId&readOnly=true",
                ref.url()
        );
        assertEquals(
                SchemaIdBuilder.build(
                        "/api/human-resources/employees",
                        "post",
                        "response",
                        true,
                        "employeeId",
                        true
                ),
                ref.schemaId()
        );
    }
}
