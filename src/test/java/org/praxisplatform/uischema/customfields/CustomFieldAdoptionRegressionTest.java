package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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

class CustomFieldAdoptionRegressionTest {
    static CustomFieldDescriptor field(String key, boolean required, boolean readOnly, Map<String,Object> expression) {
        return new CustomFieldDescriptor(key, key, "Allocation constraint", CustomFieldDataType.INTEGER,
                null, null, 0, required, readOnly, false, false, false, null, null, null, null, null,
                null, null, null, List.of(), List.of(), null, null, Map.of(),
                expression.isEmpty() ? Map.of() : Map.of("jsonLogic", expression));
    }
    static CustomFieldDescriptor amount(Map<String,Object> expression) { return field("amount",false,false,expression); }
    static CustomFieldValidator validator(CustomFieldDescriptor... fields) {
        return new CustomFieldValidator((r,t,s) -> List.of(fields));
    }
    @Test void optionalOmissionIsDistinctFromExplicitNullZeroAndRequiredInput() {
        var local = amount(Map.of(">=",List.of(Map.of("var","value"),4)));
        assertDoesNotThrow(() -> validator(local).validateValues(Map.of(),List.of(local)));
        assertDoesNotThrow(() -> validator(local).validateValues(Map.of("amount",4),List.of(local)));
        assertThrows(CustomFieldValidationException.class, () -> validator(local).validateValues(Map.of("amount",0),List.of(local)));
        var notNull = amount(Map.of("!=",Arrays.asList(Map.of("var","value"),null)));
        var explicitNull = new HashMap<String,Object>(); explicitNull.put("amount",null);
        assertDoesNotThrow(() -> validator(notNull).validateValues(explicitNull,List.of(notNull)));
        var required = field("amount",true,false,Map.of(">=",List.of(Map.of("var","value"),4)));
        assertThrows(CustomFieldValidationException.class, () -> validator(required).validateValues(Map.of(),List.of(required)));
    }
    @Test void crossFieldRequirementStillRunsWhenOptionalInputIsOmitted() {
        var conditional = amount(Map.of("if",List.of(Map.of("var","draft.requireAmount"),
                Map.of("!=",Arrays.asList(Map.of("var",Arrays.asList("value",null)),null)),true)));
        assertThrows(CustomFieldValidationException.class, () -> validator(conditional).validateValues(
                Map.of(),List.of(conditional),Map.of("requireAmount",true),null));
        assertDoesNotThrow(() -> validator(conditional).validateValues(
                Map.of(),List.of(conditional),Map.of("requireAmount",false),null));
    }
    @Test void dependencyAnalysisUsesParsedVariablesAndNestedDefaultsInsteadOfTextMatches() {
        var literal = amount(Map.of("==",List.of("draft.owner","a different literal")));
        assertDoesNotThrow(() -> validator(literal).validateValues(Map.of(),List.of(literal)));
        var bracket = amount(Map.of("==",List.of(Map.of("var","$.draft[\"owner\"]"),7)));
        var prepared = validator(bracket).prepare(new Write(null,"new",Map.of()),"allocations","a","request");
        assertTrue(prepared.requiresDraft());
        assertDoesNotThrow(() -> prepared.validateForCreate(Map.of("owner",7)));
        assertThrows(CustomFieldValidationException.class, () -> prepared.validateForCreate(Map.of("owner",8)));
        var nested = amount(Map.of("==",List.of(Map.of("var",List.of("value",Map.of("var","draft.owner"))),7)));
        assertTrue(validator(nested).prepare(new Write(null,"new",Map.of()),"allocations","a","request").requiresDraft());
        var whole = amount(Map.of("==",Arrays.asList(Map.of("var",""),null)));
        var wholePrepared = validator(whole).prepare(new Write(null,"new",Map.of()),"allocations","a","request");
        assertTrue(wholePrepared.requiresDraft());
        assertThrows(CustomFieldValidationException.class, () -> wholePrepared.validateForCreate(Map.of("owner",7)));
    }
    @Test void authoringAndPublicationShareTheSameAdmissionContract() {
        var pure = Map.<String,Object>of("jsonLogic",Map.of("<=",List.of(Map.of("var","value"),10)));
        assertDoesNotThrow(() -> CustomFieldValidationRules.validate(pure));
        assertDoesNotThrow(() -> amount((Map<String,Object>)pure.get("jsonLogic")));
        for (var expression : List.of(Map.of("now",List.of()),Map.of("daysSince",List.of("2026-01-01")),
                Map.of("var",List.of("value",Map.of("now",List.of()))))) {
            var rules = Map.<String,Object>of("jsonLogic",expression);
            assertThrows(IllegalArgumentException.class, () -> CustomFieldValidationRules.validate(rules));
            assertThrows(IllegalArgumentException.class, () -> amount((Map)expression));
        }
    }
    @Test void hydratedCreateIsValidatedBeforeBusinessHooksAndSave() {
        var fixture = new Fixture(amount(Map.of("==",List.of(Map.of("var","draft.owner"),7))));
        var created = fixture.service.create(new Write(null,"created",Map.of("amount",8)));
        assertEquals(7,created.body().owner());
        assertEquals(1,fixture.service.preparations); assertEquals(1,fixture.service.hooks);
        assertEquals(1,fixture.definitionReads.get());
        verify(fixture.repository).save(any());
    }
    @Test void rejectedHydratedCreateNeverReachesBusinessHooksOrSave() {
        var fixture = new Fixture(amount(Map.of("==",List.of(Map.of("var","draft.owner"),8))));
        assertThrows(CustomFieldValidationException.class, () -> fixture.service.create(new Write(null,"created",Map.of("amount",8))));
        assertEquals(1,fixture.service.preparations); assertEquals(0,fixture.service.hooks);
        verify(fixture.repository,never()).save(any());
    }
    @Test void ordinaryAndVersionedUpdatesKeepTheAuthorizedEntityDistinctFromTheHydratedDraft() {
        for (boolean versioned : List.of(false,true)) {
            var fixture = new Fixture(amount(Map.of("and",List.of(
                    Map.of("==",List.of(Map.of("var","draft.owner"),7)),
                    Map.of("==",List.of(Map.of("var","entity.owner"),1))))),field("audit",false,true,Map.of()));
            var request = new Write(null,"updated",Map.of("amount",8));
            if (versioned) fixture.service.update(1L,request,fixture.condition(4)); else fixture.service.update(1L,request);
            assertEquals(7,fixture.entity.owner);
            assertEquals(Map.of("amount",8,"audit",99),fixture.entity.customFields);
            assertEquals(1,fixture.definitionReads.get()); assertEquals(1,fixture.service.preparations);
        }
    }
    @Test void staleUnauthorizedAndPrimitiveInvalidRequestsCannotRunDraftPreparation() {
        var constraint = amount(Map.of("==",List.of(Map.of("var","draft.owner"),7)));
        var fixture = new Fixture(constraint);
        assertThrows(ResourceVersionPreconditionException.class, () -> fixture.service.update(1L,new Write(null,"x",Map.of("amount",8)),fixture.condition(99)));
        fixture.service.denied = true;
        assertThrows(SecurityException.class, () -> fixture.service.update(1L,new Write(null,"x",Map.of("amount",8)),fixture.condition(4)));
        fixture.service.denied = false;
        assertThrows(CustomFieldValidationException.class, () -> fixture.service.create(new Write(null,"x",Map.of("amount","wrong type"))));
        assertEquals(0,fixture.service.preparations); assertEquals(0,fixture.service.hooks);
        assertEquals(1,fixture.entity.owner); verify(fixture.repository,never()).save(any());
    }
    @Test void rejectedUpdatePreparationCannotMutateTheManagedEntity() {
        var fixture = new Fixture(amount(Map.of("==",List.of(Map.of("var","draft.owner"),8))));
        assertThrows(CustomFieldValidationException.class, () -> fixture.service.update(1L,new Write(null,"x",Map.of("amount",8)),fixture.condition(4)));
        assertEquals(1,fixture.entity.owner); assertEquals("old",fixture.entity.note);
        assertEquals(0,fixture.service.hooks); verify(fixture.repository,never()).save(any());
    }
    @Test void detachedBulkEvaluationUsesTheSameHydratedDraftWithoutWrites() throws Exception {
        var fixture = new Fixture(amount(Map.of("and",List.of(
                Map.of("==",List.of(Map.of("var","draft.owner"),7)),
                Map.of("==",List.of(Map.of("var","entity.owner"),1))))),field("audit",false,true,Map.of()));
        fixture.entity.customFields = Map.of("amount",8,"audit",99);
        var json = new ObjectMapper().findAndRegisterModules();
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var adapter = fixture.service.bulkDomainAdapter(json,factory.getValidator(),fixture.etags,()->fixture.scope);
            var fields = BulkEditableFields.compile(json,json.constructType(Write.class),json.readTree("""
                    {"type":"object","properties":{"owner":{"type":"integer"},"note":{"type":"string"},"customFields":{"type":"object"}}}
                    """),io.swagger.v3.oas.models.SpecVersion.V30,Set.of("owner","customFields"));
            adapter.evaluate(1L,fixture.etags.create(fixture.scope,"allocations",1L,4),
                    List.of(BulkFieldChange.set("note",json.valueToTree("bulk"))),BulkMode.UNIFORM_UPDATE,fields);
            assertEquals(1,fixture.entity.owner); assertEquals("old",fixture.entity.note);
            assertEquals(99,fixture.entity.customFields.get("audit"));
            assertEquals(1,fixture.definitionReads.get()); verify(fixture.repository,never()).save(any());
        }
    }
    @Test void versionedCommandsValidateEffectiveDtoAgainstPreviousFactsAndAllowServerOwnedDeterminations() {
        var rule = amount(Map.of("and", List.of(
                Map.of("==", List.of(Map.of("var", "draft.owner"), 7)),
                Map.of("==", List.of(Map.of("var", "entity.owner"), 1)))));
        var fixture = new Fixture(rule, field("audit", false, true, Map.of()));
        fixture.service.command(fixture.condition(4), current -> current.owner = 7);
        assertEquals(7, fixture.entity.owner);
        assertEquals(5, fixture.entity.version);
        assertEquals(1, fixture.definitionReads.get());
        assertEquals(0, fixture.service.hooks);

        var rejected = new Fixture(rule, field("audit", false, true, Map.of()));
        assertThrows(CustomFieldValidationException.class,
                () -> rejected.service.command(rejected.condition(4), current -> current.owner = 8));
        verify(rejected.repository, never()).save(any());

        var serverOwned = new Fixture(rule, field("audit", false, true, Map.of()));
        assertDoesNotThrow(() -> serverOwned.service.command(serverOwned.condition(4), current -> {
            current.owner = 7;
            current.customFields.put("audit", 42);
        }));
        assertEquals(42, serverOwned.entity.customFields.get("audit"));
        verify(serverOwned.repository).save(serverOwned.entity);
    }

    record Write(Integer owner, @BulkEditable(modes=BulkMode.UNIFORM_UPDATE) String note,
                 @CustomFields Map<String,Object> customFields) { }
    static class Filter implements GenericFilterDTO { }
    static class Entity {
        @jakarta.persistence.Id Long id=1L;
        @jakarta.persistence.Version long version=4;
        Integer owner=1;
        String note="old";
        @CustomFields Map<String,Object> customFields=new LinkedHashMap<>(Map.of("amount",8,"audit",99));
    }
    static class Service extends AbstractBaseResourceService<Entity,Write,Long,Filter,Write,Write> {
        int preparations, hooks;
        boolean denied;
        final ResourceMapper<Entity,Write,Write,Write,Long> mapper = new ResourceMapper<>() {
            public Write toResponse(Entity e) { return new Write(e.owner,e.note,e.customFields); }
            public Entity newEntity(Write dto) { var e=new Entity();e.owner=dto.owner();e.note=dto.note();e.customFields=dto.customFields();return e; }
            public void applyUpdate(Entity e, Write dto) { e.note=dto.note();e.customFields=dto.customFields(); }
            public Long extractId(Entity e) { return e.id; }
        };
        Service(BaseCrudRepository<Entity,Long> repository) { super(repository,Entity.class); }
        void command(ResourceVersionUpdatePrecondition<Long> condition, java.util.function.Consumer<Entity> action) {
            executeVersionedMutation(1L, condition, action);
        }
        protected ResourceMapper<Entity,Write,Write,Write,Long> getResourceMapper() { return mapper; }
        protected String resolveResourceKey() { return "allocations"; }
        protected void assertEntityAccessAllowed(Entity e) { if(denied) throw new SecurityException("Denied"); }
        protected Object prepareCreateValidationDraft(Write dto, Entity candidate) { preparations++;candidate.owner=7;return mapper.toResponse(candidate); }
        protected Object prepareUpdateValidationDraft(Long id, Entity candidate, Write dto) { preparations++;candidate.owner=7;return new Write(7,dto.note(),dto.customFields()); }
        protected void beforeCreate(Write dto,Entity e) { hooks++; }
        protected void beforeUpdate(Long id,Entity e,Write dto) { hooks++; }
        protected void applyResourceUpdate(Long id,Entity e,Write dto) { super.applyResourceUpdate(id,e,dto);e.owner=7; }
    }
    @SuppressWarnings("unchecked")
    static class Fixture {
        final BaseCrudRepository<Entity,Long> repository=mock(BaseCrudRepository.class);
        final Entity entity=new Entity();
        final Service service=new Service(repository);
        final AtomicInteger definitionReads=new AtomicInteger();
        final ResourceVersionEtagService etags=new ResourceVersionEtagService("canonical-custom-field-regression-secret");
        final ResourceVersionScope scope=new ResourceVersionScope("tenant=a");
        Fixture(CustomFieldDescriptor... fields) {
            when(repository.findById(1L)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(call -> { Entity e=call.getArgument(0);e.version++;return e; });
            ReflectionTestUtils.setField(service,"customFieldValidator",new CustomFieldValidator((r,t,s)->{
                definitionReads.incrementAndGet();return List.of(fields);
            }));
        }
        ResourceVersionUpdatePrecondition<Long> condition(long version) {
            return new ResourceVersionUpdatePrecondition<>(etags,etags.create(scope,"allocations",1L,version),scope,"allocations",1L);
        }
    }
}
