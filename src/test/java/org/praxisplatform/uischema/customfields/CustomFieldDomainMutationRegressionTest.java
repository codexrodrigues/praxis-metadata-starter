package org.praxisplatform.uischema.customfields;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.dto.PartialUpdateInput;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.*;
import org.springframework.test.util.ReflectionTestUtils;

@SuppressWarnings({"rawtypes", "unchecked"})
class CustomFieldDomainMutationRegressionTest {
  record Write(@CustomFields Map<String, Object> customFields) { }
  static class Filter implements GenericFilterDTO { }
  public static class Row {
    @Id Long id = 1L;
    @Version long version = 4;
    Map<String, Object> customFields = new LinkedHashMap<>(Map.of("amount", 8, "audit", 99));
  }
  public static class AnnotatedRow extends Row {
    @CustomFields public Map<String, Object> getCustomFields() { return customFields; }
  }
  public static class RelatedRow extends AnnotatedRow {
    @jakarta.persistence.ManyToOne RelatedDepartment department = new RelatedDepartment();
    public RelatedDepartment getDepartment() { throw new AssertionError("Unrelated association must not be read"); }
  }
  @jakarta.persistence.Entity public static class RelatedDepartment {
    @Id Long id = 2L;
    String name = "Legal";
  }
  static CustomFieldDescriptor field(String key, boolean readonly, Map<String, Object> expression) {
    return new CustomFieldDescriptor(key, key, "Review constraint", CustomFieldDataType.INTEGER,
        null, null, 0, false, readonly, false, false, false, null, null, null, null, null, null, null, null,
        List.of(), List.of(), null, null, Map.of(), expression.isEmpty() ? Map.of() : Map.of("jsonLogic", expression));
  }
  static class Service<E extends Row> extends AbstractBaseResourceService<E, Write, Long, Filter, Write, Write> {
    final BaseCrudRepository<E, Long> repository;
    java.util.function.Consumer<E> hook = e -> { };
    final ResourceMapper<E, Write, Write, Write, Long> mapper = new ResourceMapper<>() {
      public E newEntity(Write value) { throw new UnsupportedOperationException(); }
      public Write toResponse(E row) { return new Write(row.customFields); }
      public void applyUpdate(E row, Write value) { row.customFields = new LinkedHashMap<>(value.customFields()); }
      public Long extractId(E row) { return row.id; }
    };
    Service(BaseCrudRepository<E, Long> repository, Class<E> type) { super(repository, type); this.repository = repository; }
    protected ResourceMapper<E, Write, Write, Write, Long> getResourceMapper() { return mapper; }
    protected String resolveResourceKey() { return "review.rows"; }
    protected void beforeUpdate(Long id, E row, Write dto) { hook.accept(row); }
    void command(ResourceVersionUpdatePrecondition<Long> condition, java.util.function.Consumer<E> mutation) {
      executeVersionedMutation(1L, condition, mutation);
    }
  }
  static <E extends Row> Service<E> service(E row, CustomFieldDescriptor... fields) {
    BaseCrudRepository<E, Long> repository = mock(BaseCrudRepository.class);
    when(repository.findById(1L)).thenReturn(Optional.of(row));
    when(repository.save(any())).thenAnswer(call -> { row.version++; return row; });
    var service = new Service<>(repository, (Class<E>) row.getClass());
    ReflectionTestUtils.setField(service, "customFieldValidator", new CustomFieldValidator((r, t, s) -> List.of(fields)));
    return service;
  }
  static ResourceVersionUpdatePrecondition<Long> condition(long version) {
    var etags = new ResourceVersionEtagService("isolated-review-secret");
    return new ResourceVersionUpdatePrecondition<>(etags, etags.create("review.rows", 1L, version), "review.rows", 1L);
  }

  @Test void dtoOnlyCustomFieldsMustNotBypassRulesInVersionedCommands() {
    var row = new Row(); row.customFields = new LinkedHashMap<>(Map.of("amount", 8));
    var amount = field("amount", false, Map.of(">=", List.of(Map.of("var", "value"), 4)));
    var service = service(row, amount);
    assertThrows(CustomFieldValidationException.class,
        () -> service.update(1L, new Write(Map.of("amount", 0)), condition(4)));
    assertThrows(CustomFieldValidationException.class,
        () -> service.command(condition(4), current -> current.customFields.put("amount", 0)),
        "A DTO-annotated resource must enforce the same rule even when its JPA entity has no @CustomFields marker.");
    verify(service.repository, never()).save(any());
  }

  @Test void trustedCommandMayRefreshAServerOwnedReadonlyFieldAsAnOrdinaryDomainHookCan() {
    var amount = field("amount", false, Map.of()); var audit = field("audit", true, Map.of());
    var ordinary = new AnnotatedRow(); var put = service(ordinary, amount, audit);
    put.hook = current -> current.customFields.put("audit", 42);
    assertDoesNotThrow(() -> put.update(1L, new Write(Map.of("amount", 8)), condition(4)));
    assertEquals(42, ordinary.customFields.get("audit"));
    var command = new AnnotatedRow(); var actions = service(command, amount, audit);
    assertDoesNotThrow(() -> actions.command(condition(4), current -> current.customFields.put("audit", 42)),
        "The server-owned change is authorized domain behavior, not a submitted read-only assignment.");
    assertEquals(42, command.customFields.get("audit"));
  }

  @Test void valueOnlyCommandMustNotRequireADeepSnapshotOfAnUnrelatedJpaAssociation() {
    var amount = field("amount", false, Map.of(">=", List.of(Map.of("var", "value"), 4)));
    var audit = field("audit", true, Map.of());
    var ordinary = new RelatedRow(); var put = service(ordinary, amount, audit);
    assertDoesNotThrow(() -> put.update(1L, new Write(Map.of("amount", 9)), condition(4)));
    var command = new RelatedRow(); var actions = service(command, amount, audit);
    assertDoesNotThrow(() -> actions.command(condition(4), current -> current.customFields.put("amount", 9)),
        "An unrelated @ManyToOne must not break a value-only command before its callback or validator runs.");
  }

  @Test void entityFactsAndDefinitionsAreCapturedBeforeTheMutationWithoutAliasingTheJsonContainer() {
    var row = new Row(); row.customFields = new LinkedHashMap<>(Map.of("amount", 8));
    var limit = field("amount", false, Map.of("<=", List.of(Map.of("var", "value"), Map.of("var", "entity.customFields.amount"))));
    var actions = service(row, limit);
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    ReflectionTestUtils.setField(actions, "customFieldValidator", new CustomFieldValidator((r,t,s) -> {
      reads.incrementAndGet(); return List.of(limit);
    }));
    assertThrows(CustomFieldValidationException.class,
        () -> actions.command(condition(4), current -> current.customFields.put("amount", 10)));
    assertEquals(1, reads.get()); verify(actions.repository, never()).save(any());
  }

  @Test void aTrustedCommandCanRepairAnOldValueThatNoLongerMeetsTheActiveDefinition() {
    var row = new Row(); row.customFields = new LinkedHashMap<>(Map.of("amount", 0));
    var actions = service(row, field("amount", false, Map.of(">=", List.of(Map.of("var", "value"), 4))));
    assertDoesNotThrow(() -> actions.command(condition(4), current -> current.customFields.put("amount", 8)));
    assertEquals(8, row.customFields.get("amount"));
  }

  @Test void readOnlyClientAssignmentsStillFailBeforeTheDomainHookOrSave() {
    var row = new AnnotatedRow(); var actions = service(row, field("amount", false, Map.of()), field("audit", true, Map.of()));
    var calls = new java.util.concurrent.atomic.AtomicInteger(); actions.hook = ignored -> calls.incrementAndGet();
    assertThrows(CustomFieldValidationException.class,
        () -> actions.update(1L, new Write(Map.of("amount", 8, "audit", 42)), condition(4)));
    assertEquals(0, calls.get()); assertEquals(99, row.customFields.get("audit"));
    verify(actions.repository, never()).save(any());
  }

  @Test void standaloneValidationUsesNoPriorValuesAndNeverTreatsSubmittedReadonlyAsPersisted() {
    var amount = field("amount", false, Map.of(">=", List.of(Map.of("var", "value"), 4)));
    var audit = field("audit", true, Map.of());
    var validator = new CustomFieldValidator((r, t, s) -> List.of(amount, audit));
    assertThrows(CustomFieldValidationException.class, () -> validator.validateAndGetReadOnlyKeys(
        new Write(Map.of("amount", 0)), "review.rows", "tenant", "request", null));
    var dto = new Write(Map.of("amount", 8, "audit", 99));
    assertThrows(CustomFieldValidationException.class, () -> validator.validateAndGetReadOnlyKeys(
        dto, "review.rows", "tenant", "request", null));
    assertEquals(Set.of("audit"), validator.validateAndGetReadOnlyKeys(
        dto, "review.rows", "tenant", "request", new AnnotatedRow()));
  }

  @Test void partialAnnotatedResponseCannotHideAnInvalidPersistedCustomField() {
    var row = new AnnotatedRow();
    BaseCrudRepository<AnnotatedRow, Long> repository = mock(BaseCrudRepository.class);
    when(repository.findById(1L)).thenReturn(Optional.of(row));
    var service = new Service<>(repository, AnnotatedRow.class) {
      @Override protected ResourceMapper<AnnotatedRow, Write, Write, Write, Long> getResourceMapper() {
        return new ResourceMapper<>() {
          public AnnotatedRow newEntity(Write input) { throw new UnsupportedOperationException(); }
          public Write toResponse(AnnotatedRow value) { return new Write(Map.of()); }
          public void applyUpdate(AnnotatedRow value, Write input) { throw new UnsupportedOperationException(); }
          public Long extractId(AnnotatedRow value) { return value.id; }
        };
      }
    };
    var amount = field("amount", false, Map.of(">=", List.of(Map.of("var", "value"), 4)));
    var audit = field("audit", true, Map.of());
    ReflectionTestUtils.setField(service, "customFieldValidator",
        new CustomFieldValidator((r, t, s) -> List.of(amount, audit)));

    assertThrows(CustomFieldValidationException.class,
        () -> service.command(condition(4), current -> current.customFields.put("amount", 0)));
    verify(repository, never()).save(any());
  }

  @Test void versionedCommandValidatesPersistedCustomFieldsWhenTheResponseOmitsThem() {
    var row = new AnnotatedRow();
    BaseCrudRepository<AnnotatedRow, Long> repository = mock(BaseCrudRepository.class);
    when(repository.findById(1L)).thenReturn(Optional.of(row));
    var service = new AbstractBaseResourceService<AnnotatedRow, Long, Long, Filter, Write, Write>(repository, AnnotatedRow.class) {
      protected ResourceMapper<AnnotatedRow, Long, Write, Write, Long> getResourceMapper() {
        return new ResourceMapper<>() {
          public AnnotatedRow newEntity(Write input) { throw new UnsupportedOperationException(); }
          public Long toResponse(AnnotatedRow value) { return value.id; }
          public void applyUpdate(AnnotatedRow value, Write input) { throw new UnsupportedOperationException(); }
          public Long extractId(AnnotatedRow value) { return value.id; }
        };
      }
      protected String resolveResourceKey() { return "review.rows"; }
      void command() { executeVersionedMutation(1L, condition(4), current -> current.customFields.put("amount", 0)); }
    };
    var amount = field("amount", false, Map.of(">=", List.of(Map.of("var", "value"), 4)));
    var audit = field("audit", true, Map.of());
    ReflectionTestUtils.setField(service, "customFieldValidator", new CustomFieldValidator((r, t, s) -> List.of(amount, audit)));
    assertThrows(CustomFieldValidationException.class, service::command);
    verify(repository, never()).save(any());
  }

}
