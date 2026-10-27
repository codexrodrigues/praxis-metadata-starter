package org.praxisplatform.uischema.customfields;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomFieldValidatorTest {
    record Payload(@CustomFields(resourceKey="people") Map<String, Object> customFields) {}
    private CustomFieldValidator validator(CustomFieldDescriptor d) { return new CustomFieldValidator((r,t,s) -> List.of(d)); }
    @Test void readsRecordsAndRejectsUnknownKeys() {
        var ex = assertThrows(CustomFieldValidationException.class, () -> validator(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER))
                .validate(new Payload(Map.of("typo", 2)), "people", "tenant-a", "request"));
        assertEquals(Set.of("customFields.typo", "customFields.age"), ex.getFieldErrors().stream().map(e -> e.getField()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void usesAnnotationResourceAndTenantOperation() {
        var provider = mock(CustomFieldDefinitionProvider.class);
        when(provider.findDefinitions("people", "a", "request")).thenReturn(List.of(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER)));
        new CustomFieldValidator(provider).validate(new Payload(Map.of("age", 40)), "fallback", "a", "request");
        verify(provider).findDefinitions("people", "a", "request");
    }
    @Test void doesNotCallProviderForOrdinaryDto() {
        var provider = mock(CustomFieldDefinitionProvider.class);
        new CustomFieldValidator(provider).validate("ordinary", "people", "a", "request");
        verifyNoInteractions(provider);
    }
    @Test void validatesAllTypesWithoutCoercingStringsIntoNumbers() {
        var values = Map.of(CustomFieldDataType.STRING, "ab", CustomFieldDataType.INTEGER, 4,
                CustomFieldDataType.DECIMAL, new BigDecimal("1.4"), CustomFieldDataType.BOOLEAN, true,
                CustomFieldDataType.DATE, "2026-10-27", CustomFieldDataType.DATE_TIME, "2026-10-27T12:30:00Z",
                CustomFieldDataType.ENUM, "ab", CustomFieldDataType.ARRAY, List.of(1, 2));
        values.forEach((type,value) -> assertDoesNotThrow(() -> validator(CustomFieldFixtures.field("field", type)).validate(new Payload(Map.of("field", value)), "people", null)));
        assertThrows(CustomFieldValidationException.class, () -> validator(CustomFieldFixtures.field("field", CustomFieldDataType.INTEGER)).validate(new Payload(Map.of("field", "42")), "people", null));
        assertFalse(CustomFieldValidator.matchesType(CustomFieldFixtures.field("field", CustomFieldDataType.DATE), "2026-02-30"));
        assertFalse(CustomFieldValidator.matchesType(CustomFieldFixtures.field("field", CustomFieldDataType.DECIMAL), Double.NaN));
        assertFalse(CustomFieldValidator.matchesType(CustomFieldFixtures.field("field", CustomFieldDataType.INTEGER), 2.2));
    }
    @Test void validatesBoundsAndPattern() {
        assertThrows(CustomFieldValidationException.class, () -> validator(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER)).validate(new Payload(Map.of("age", 101)), "people", null));
        assertThrows(CustomFieldValidationException.class, () -> validator(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER)).validate(new Payload(Map.of("age", 0)), "people", null));
        assertThrows(CustomFieldValidationException.class, () -> validator(CustomFieldFixtures.field("name", CustomFieldDataType.STRING)).validate(new Payload(Map.of("name", "a")), "people", null));
        var patterned = new CustomFieldDescriptor("code", "Code", null, CustomFieldDataType.STRING, null, null, 0, false, false,
                false, false, false, null, null, null, null, null, "[A-Z]+", "Uppercase required", null, null, null, null, null, null);
        var ex = assertThrows(CustomFieldValidationException.class, () -> validator(patterned).validate(new Payload(Map.of("code", "lower")), "people", null));
        assertEquals("Uppercase required", ex.getFieldErrors().getFirst().getDefaultMessage());
    }
    @Test void nullContainerStillChecksRequiredDefinitions() {
        assertThrows(CustomFieldValidationException.class, () -> validator(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER)).validate(new Payload(null), "people", null));
    }
    @Test void snapshotsNestedMetadataAndRejectsUnsafeDefinitionKeys() {
        assertThrows(IllegalArgumentException.class, () -> CustomFieldFixtures.field("a.b", CustomFieldDataType.STRING));
        Map<String,Object> nested = new LinkedHashMap<>(); nested.put("roles", new ArrayList<>(List.of("user")));
        var d = new CustomFieldDescriptor("code", "Code", null, CustomFieldDataType.STRING, null, null, 0, false, false, false, false, false,
                null, null, null, null, null, null, null, null, null, List.of(nested), null, null, nested);
        ((List<String>)nested.get("roles")).add("admin");
        assertEquals(List.of("user"), d.governance().get("roles"));
        assertThrows(UnsupportedOperationException.class, () -> d.options().getFirst().put("x", 1));
    }

    @Test void readOnlyRequiredFieldsAreNotRequiredOnWritesButAnySubmittedAssignmentIsRejected() {
        var readOnly = new CustomFieldDescriptor("calculated",null,null,CustomFieldDataType.INTEGER,null,null,0,true,true,
                false,false,false,null,null,null,null,null,null,null,null,null,null,null,null,null);
        var validator = validator(readOnly);
        assertDoesNotThrow(() -> validator.validate(new Payload(Map.of()),"people",null));
        assertThrows(CustomFieldValidationException.class,() -> validator.validate(new Payload(Map.of("calculated",42)),"people",null));
        Map<String,Object> explicitNull = new LinkedHashMap<>(); explicitNull.put("calculated",null);
        assertThrows(CustomFieldValidationException.class,() -> validator.validate(new Payload(explicitNull),"people",null));
    }
    @Test void optionalNullIsAllowedAndRequiredNullUsesConfiguredFieldMessage() {
        var optional = new CustomFieldDescriptor("note",null,null,CustomFieldDataType.STRING,null,null,0,false,false,
                false,false,false,null,null,null,null,null,null,null,null,null,null,null,null,null);
        Map<String,Object> explicitNull = new LinkedHashMap<>(); explicitNull.put("note",null);
        assertDoesNotThrow(() -> validator(optional).validate(new Payload(explicitNull),"people",null));
        explicitNull.clear(); explicitNull.put("age",null);
        var error = assertThrows(CustomFieldValidationException.class,() -> validator(CustomFieldFixtures.field("age",CustomFieldDataType.INTEGER))
                .validate(new Payload(explicitNull),"people",null));
        assertEquals("Required!",error.getFieldErrors().getFirst().getDefaultMessage());
        assertEquals("customFields.age",error.getFieldErrors().getFirst().getField());
    }
    @Test void excessiveLengthAndInvalidArrayTypeAreRejected() {
        assertThrows(CustomFieldValidationException.class,() -> validator(CustomFieldFixtures.field("name",CustomFieldDataType.STRING))
                .validate(new Payload(Map.of("name","a".repeat(21))),"people",null));
        assertThrows(CustomFieldValidationException.class,() -> validator(CustomFieldFixtures.field("items",CustomFieldDataType.ARRAY))
                .validate(new Payload(Map.of("items",Map.of("unexpected",1))),"people",null));
    }

    @org.praxisplatform.uischema.annotation.ApiResource(resourceKey="   ")
    static class BlankResourceKey { }
    @org.praxisplatform.uischema.annotation.ApiResource(resourceKey="  hr.people  ")
    static class PaddedResourceKey { }
    record PaddedCustomKey(@CustomFields(resourceKey="  hr.customPeople  ") Map<String,Object> customFields) { }
    @org.praxisplatform.uischema.annotation.ApiResource(resourceKey="  hr.people  ")
    record BlankCustomKey(@CustomFields(resourceKey="  ") Map<String,Object> customFields) { }
    @Test void resolvesBlankResourceKeysToFallbackAndTrimsDeclaredKeys() {
        assertEquals("entityFallback",CustomFieldSupport.resourceKey(BlankResourceKey.class,"entityFallback"));
        assertEquals("entityFallback",CustomFieldSupport.resourceKey(String.class,"entityFallback"));
        assertEquals("hr.people",CustomFieldSupport.resourceKey(PaddedResourceKey.class,"entityFallback"));
        assertEquals("hr.customPeople",CustomFieldSupport.resourceKey(PaddedCustomKey.class,"entityFallback"));
        assertEquals("hr.people",CustomFieldSupport.resourceKey(BlankCustomKey.class,"entityFallback"));
    }
}
