package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P5ACustomFieldRegressionTest {
    private static CustomFieldDescriptor amount(Map<String,Object> expression) {
        return CustomFieldAdoptionRegressionTest.amount(expression);
    }
    private static CustomFieldValidator validator(CustomFieldDescriptor... definitions) {
        return new CustomFieldValidator((r,t,s) -> List.of(definitions));
    }

    @Test void optionalCrossFieldComparisonTreatsOmittedAndExplicitNullAsNotApplicable() {
        var definition = amount(Map.of("<=", List.of(Map.of("var", "value"), Map.of("var", "draft.limit"))));
        var explicitNull = new HashMap<String,Object>(); explicitNull.put("amount", null);
        for (var values : List.of(Map.<String,Object>of(), explicitNull))
            assertDoesNotThrow(() -> validator(definition).validateValues(values, List.of(definition), Map.of("limit", 10), null));
        assertDoesNotThrow(() -> validator(definition).validateValues(Map.of("amount", 8), List.of(definition), Map.of("limit", 10), null));
        assertThrows(CustomFieldValidationException.class, () -> validator(definition).validateValues(
                Map.of("amount", 11), List.of(definition), Map.of("limit", 10), null));
        assertThrows(IllegalStateException.class, () -> validator(definition).validateValues(
                Map.of("amount", 8), List.of(definition), Map.of("limit", "wrong type"), null));
    }

    @Test void conditionalRequiredStillRejectsEmptyValuesAndStandaloneRequiresTrustedEntity() {
        var conditional = amount(Map.of("if", List.of(Map.of("var", "draft.required"),
                Map.of("!=", Arrays.asList(Map.of("var", "value"), null)), true)));
        var explicitNull = new HashMap<String,Object>(); explicitNull.put("amount", null);
        for (var values : List.of(Map.<String,Object>of(), explicitNull)) {
            assertThrows(CustomFieldValidationException.class, () -> validator(conditional).validateValues(
                    values, List.of(conditional), Map.of("required", true), null));
            assertDoesNotThrow(() -> validator(conditional).validateValues(values, List.of(conditional), Map.of("required", false), null));
        }
        var entityRule = amount(Map.of("==", Arrays.asList(Map.of("var", "entity"), null)));
        var prepared = validator(entityRule).prepare(new Write("old", null, Map.of("amount", 8)), "allocations", "a", "request");
        var failure = assertThrows(IllegalStateException.class, prepared::validateWithoutEntity);
        assertEquals("Custom field rules require the current entity", failure.getMessage());
        assertDoesNotThrow(() -> { prepared.validateForCreate(); });
    }

    @Test void iteratorLocalWholeValueDoesNotRequireTheServerEntity() {
        for (String operator : List.of("all", "some", "none", "map", "filter")) {
            var expression = Map.<String,Object>of(operator, List.of(List.of(1,2), Map.of(">", List.of(Map.of("var", ""), operator.equals("none") ? 3 : 0))));
            assertFalse(CustomFieldJsonLogic.requiresEntity(CustomFieldJsonLogic.expression(Map.of("jsonLogic", expression))), operator);
            assertDoesNotThrow(() -> validator(amount(expression)).validateValues(Map.of("amount", 8), List.of(amount(expression))));
        }
        assertTrue(CustomFieldJsonLogic.requiresEntity(CustomFieldJsonLogic.expression(Map.of("jsonLogic", Map.of("var", "")))));
    }

    @Test void readOnlySnapshotsAreAcceptedOnlyWhenEqualToThePersistedValue() {
        var audit = CustomFieldAdoptionRegressionTest.field("audit", false, true, Map.of());
        var validator = validator(audit);
        var entity = new Entity();
        var same = new Write("new", null, Map.of("audit", 99L));
        assertDoesNotThrow(() -> validator.prepare(same, "allocations", "a", "request").validateForUpdate(entity));
        assertThrows(CustomFieldValidationException.class, () -> validator.prepare(same, "allocations", "a", "request").validateForCreate());
        for (Object changed : Arrays.asList(98, null)) {
            var values = new HashMap<String,Object>(); values.put("audit", changed);
            var prepared = validator.prepare(new Write("new", null, values), "allocations", "a", "request");
            assertThrows(CustomFieldValidationException.class, () -> prepared.validateForUpdate(entity));
        }
        assertDoesNotThrow(() -> validator.prepare(new Write("new", null, Map.of()), "allocations", "a", "request").validateForUpdate(entity));
        var submitted = new HashMap<String,Object>(); submitted.put("audit", 99);
        var prepared = validator.prepare(new Write("new", null, submitted), "allocations", "a", "request");
        submitted.put("audit", 999);
        assertDoesNotThrow(() -> prepared.validateForUpdate(entity));
    }

    @Test void defaultDraftCombinesNewDtoValuesAndExistingHydratedStateForPutAndBulk() throws Exception {
        var rule = amount(Map.of("and", List.of(
                Map.of("==", List.of(Map.of("var", "draft.note"), "new")),
                Map.of("==", List.of(Map.of("var", "draft.owner"), 7)),
                Map.of("==", List.of(Map.of("var", "entity.note"), "old")))));
        for (boolean bulk : List.of(false, true)) {
            @SuppressWarnings("unchecked") var repository = (BaseCrudRepository<Entity,Long>)mock(BaseCrudRepository.class);
            var entity = new Entity();
            when(repository.findById(1L)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
            var service = new Service(repository);
            ReflectionTestUtils.setField(service, "customFieldValidator", validator(rule,
                    CustomFieldAdoptionRegressionTest.field("audit", false, true, Map.of())));
            if (!bulk) {
                service.update(1L, new Write("new", null, Map.of("amount",8,"audit",99)));
                assertEquals("new", entity.note);
                assertEquals(7, entity.owner);
            } else {
                var mapper = new ObjectMapper();
                try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                    var etags = new ResourceVersionEtagService("p5a-draft-secret");
                    var adapter = service.bulkDomainAdapter(mapper, factory.getValidator(), etags, () -> ResourceVersionScope.GLOBAL);
                    var fields = BulkEditableFields.compile(mapper, mapper.constructType(Write.class), mapper.readTree("""
                            {"type":"object","properties":{"note":{"type":"string"},"owner":{"type":"integer"},"customFields":{"type":"object"}}}
                            """), io.swagger.v3.oas.models.SpecVersion.V30, Set.of("owner", "customFields"));
                    adapter.evaluate(1L, etags.create("allocations",1L,4), List.of(BulkFieldChange.set("note",mapper.valueToTree("new"))),
                            BulkMode.UNIFORM_UPDATE, fields);
                    assertEquals("old", entity.note);
                    assertEquals(7, entity.owner);
                    verify(repository, never()).save(any());
                }
            }
        }
    }

    record Write(@BulkEditable(modes = BulkMode.UNIFORM_UPDATE) String note, Integer owner,
                 @CustomFields Map<String,Object> customFields) { }
    static class Filter implements GenericFilterDTO { }
    static class Entity {
        @jakarta.persistence.Id Long id = 1L;
        @jakarta.persistence.Version long revision = 4;
        String note = "old";
        Integer owner = 7;
        @CustomFields Map<String,Object> customFields = Map.of("amount",8,"audit",99);
    }
    static class Service extends AbstractBaseResourceService<Entity,Write,Long,Filter,Write,Write> {
        Service(BaseCrudRepository<Entity,Long> repository) { super(repository, Entity.class); }
        protected String resolveResourceKey() { return "allocations"; }
        protected ResourceMapper<Entity,Write,Write,Write,Long> getResourceMapper() {
            return new ResourceMapper<>() {
                public Write toResponse(Entity e) { return new Write(e.note,e.owner,e.customFields); }
                public Entity newEntity(Write dto) { return new Entity(); }
                public void applyUpdate(Entity e, Write dto) {
                    e.note = dto.note();
                    if (dto.owner() != null) e.owner = dto.owner();
                    e.customFields = dto.customFields();
                }
                public Long extractId(Entity e) { return e.id; }
            };
        }
    }
}
