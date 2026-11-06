package org.praxisplatform.uischema.service.base;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.SpecVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Version;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercises the native adapter and its domain hooks with persistence collaborators substituted. */
class BulkDecimalAdapterTest {
    static final BigDecimal PREVIOUS = new BigDecimal("2.500");
    final ObjectMapper json = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNumberLength(48).build()).build());
    final EntityManager em = mock(EntityManager.class);
    final Rows rows = mock(Rows.class);
    final Row row = new Row();
    final Service service = new Service(rows);
    final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    final ResourceVersionEtagService etags = new ResourceVersionEtagService("decimal-adapter-test");
    final String version = etags.create(ResourceVersionScope.GLOBAL, "prices", 1L, 0);
    final BulkDomainResourceAdapter<Long, Filter> adapter = service.bulkDomainAdapter(
            json, validators.getValidator(), etags, () -> ResourceVersionScope.GLOBAL);
    BulkEditableFields fields;

    @BeforeEach void prepare() throws Exception {
        ReflectionTestUtils.setField(service, "entityManager", em);
        when(em.isJoinedToTransaction()).thenReturn(true);
        when(em.unwrap(Session.class)).thenReturn(mock(Session.class));
        when(rows.findById(1L)).thenReturn(Optional.of(row));
        when(em.find(Row.class, 1L)).thenReturn(row);
        when(em.contains(row)).thenReturn(true);
        when(rows.save(row)).thenAnswer(invocation -> { row.version++; return row; });
        fields = BulkEditableFields.compile(json, json.constructType(Write.class), json.readTree("""
                {"type":"object","properties":{"amount":{"type":"string","format":"decimal","nullable":true},
                                               "note":{"type":"string"}}}
                """), SpecVersion.V30, Set.of());
    }

    @AfterEach void close() { validators.close(); }

    @ParameterizedTest @EnumSource(value=BulkMode.class, names={"PER_ITEM_UPDATE", "UNIFORM_UPDATE"})
    void strictHostPreservesAnOmittedDecimalFromProjectionThroughConfirmation(BulkMode mode) {
        var host = json.copy().disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        var strictAdapter = service.bulkDomainAdapter(host, validators.getValidator(), etags, () -> ResourceVersionScope.GLOBAL);
        var evaluation = ResourceMutationContext.bulkEvaluation(mode, Instant.EPOCH);
        var confirmation = ResourceMutationContext.bulkConfirmation(mode, Instant.EPOCH);
        var change = BulkFieldChange.set("note", host.getNodeFactory().textNode("revised"));
        var candidate = strictAdapter.evaluate(1L, version, List.of(change), evaluation, fields);
        assertEquals("2.500", candidate.plan().get("amount").textValue());
        assertEquals(PREVIOUS, service.admittedAmount);
        assertEquals("old", row.note);
        verify(rows, never()).save(any());
        var invalid = BulkFieldChange.set("amount", host.getNodeFactory().numberNode(5));
        assertThrows(BulkFieldChangeValidationException.class,
                () -> strictAdapter.evaluate(1L, version, List.of(invalid), evaluation, fields));
        strictAdapter.update(1L, version, candidate.plan(), confirmation);
        assertEquals(PREVIOUS, row.amount);
        assertEquals("revised", row.note);
        assertEquals(confirmation, service.admittedContext);
        verify(rows).save(row);
    }

    @org.junit.jupiter.api.Test void numericResponseProjectsExactlyIntoATextualUpdatePlan() {
        service.numericResponse = true;
        row.amount = new BigDecimal("12345678901234567890.00123400");
        var evaluation = ResourceMutationContext.bulkEvaluation(BulkMode.PER_ITEM_UPDATE, Instant.EPOCH);
        var keepAmount = BulkFieldChange.set("note", json.getNodeFactory().textNode("revised"));
        var candidate = adapter.evaluate(1L, version, List.of(keepAmount), evaluation, fields);
        assertEquals(row.amount.toString(), candidate.plan().get("amount").textValue());
        assertFalse(candidate.plan().has("responseOnly"));
        assertEquals(row.amount, service.admittedAmount);
        verify(rows, never()).save(any());
    }

    @ParameterizedTest @EnumSource(value=BulkMode.class, names={"PER_ITEM_UPDATE", "UNIFORM_UPDATE"})
    void evaluationAndConfirmationPreserveTheTextualAmount(BulkMode mode) {
        var evaluation = ResourceMutationContext.bulkEvaluation(mode, Instant.EPOCH);
        var confirmation = ResourceMutationContext.bulkConfirmation(mode, Instant.EPOCH);
        var note = BulkFieldChange.set("note", json.getNodeFactory().textNode("revised"));
        var omitted = adapter.evaluate(1L, version, List.of(note), evaluation, fields);
        assertEquals("2.500", omitted.plan().get("amount").textValue());

        var zero = adapter.evaluate(1L, version, List.of(setAmount("0")), evaluation, fields);
        assertEquals("0", zero.plan().get("amount").textValue());
        var cleared = adapter.evaluate(1L, version, List.of(BulkFieldChange.clear("amount")), evaluation, fields);
        assertTrue(cleared.plan().get("amount").isNull());
        assertThrows(BulkFieldChangeValidationException.class, () ->
                adapter.evaluate(1L, version, List.of(BulkFieldChange.clear("note")), evaluation, fields));

        String exact = "123456789012345678901234567890.0123400";
        var candidate = adapter.evaluate(1L, version, List.of(setAmount(exact)), evaluation, fields);
        assertEquals(exact, candidate.plan().get("amount").textValue());
        assertEquals(PREVIOUS, row.amount);
        verify(rows, never()).save(any());
        adapter.update(1L, version, candidate.plan(), confirmation);
        assertEquals(new BigDecimal(exact), row.amount);
        assertEquals(row.amount, service.admittedAmount);
        assertEquals(confirmation, service.admittedContext);
        verify(em).refresh(row, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(ResourceVersionPreconditionException.class,
                () -> adapter.update(1L, version, candidate.plan(), confirmation));
        verify(rows).save(row);
    }

    @ParameterizedTest @EnumSource(value=BulkMode.class, names={"PER_ITEM_UPDATE", "UNIFORM_UPDATE"})
    void invalidInputStopsBeforeAdmissionAndDomainDenialStopsBeforeSave(BulkMode mode) throws Exception {
        var evaluation = ResourceMutationContext.bulkEvaluation(mode, Instant.EPOCH);
        var confirmation = ResourceMutationContext.bulkConfirmation(mode, Instant.EPOCH);
        for (String token : List.of("5", "5.0", "true", "{}", "[]", "\"1,5\"", "\"" + "1".repeat(49) + "\"")) {
            // Native bulk ingress represents a fractional number with DecimalNode, never DoubleNode.
            JsonNode value = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(token);
            var change = BulkFieldChange.set("amount", value);
            assertThrows(BulkFieldChangeValidationException.class, () ->
                    adapter.evaluate(1L, version, List.of(change), evaluation, fields));
            com.fasterxml.jackson.databind.node.ObjectNode plan = json.valueToTree(new Write(row.amount, row.note));
            plan.set("amount", value);
            var invalid = assertThrows(IllegalArgumentException.class, () -> adapter.update(1L, version, plan, confirmation));
            assertInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class, invalid.getCause());
        }
        assertNull(service.admittedContext);

        var plan = json.valueToTree(new Write(new BigDecimal("5"), row.note));
        assertEquals("5", plan.get("amount").textValue());
        service.deny = true;
        assertThrows(DomainDenial.class, () -> adapter.update(1L, version, plan, confirmation));
        assertEquals(new BigDecimal("5"), service.admittedAmount);
        assertEquals(PREVIOUS, row.amount);
        assertEquals(0, row.version);
        verify(rows, never()).save(any());
    }

    BulkFieldChange setAmount(String value) {
        return BulkFieldChange.set("amount", json.getNodeFactory().textNode(value));
    }

    static class Amount {
        @BulkEditable(allowClear=true) @JsonFormat(shape=JsonFormat.Shape.STRING)
        public BigDecimal amount;
    }
    static class Write extends Amount {
        @BulkEditable public String note;
        public Write() { }
        Write(BigDecimal amount, String note) { this.amount = amount; this.note = note; }
    }
    static class Row {
        @Version long version;
        BigDecimal amount = PREVIOUS;
        String note = "old";
    }
    interface Rows extends BaseCrudRepository<Row, Long> { }
    static class Filter implements GenericFilterDTO { }
    static class DomainDenial extends RuntimeException { }
    interface View { }
    record TextView(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount, String note, String responseOnly) implements View { }
    record NumericView(BigDecimal amount, String note, String responseOnly) implements View { }
    static class Service extends AbstractBaseResourceService<Row, View, Long, Filter, Write, Write> {
        boolean deny;
        boolean numericResponse;
        BigDecimal admittedAmount;
        ResourceMutationContext admittedContext;
        Service(Rows rows) { super(rows, Row.class); }
        protected String resolveResourceKey() { return "prices"; }
        protected JsonNode validateUpdate(ResourceMutationContext context, Long id, Row current, Write dto) {
            admittedContext = context;
            admittedAmount = dto.amount;
            if (deny) throw new DomainDenial();
            return null;
        }
        protected ResourceMapper<Row, View, Write, Write, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                public View toResponse(Row entity) {
                    return numericResponse ? new NumericView(entity.amount, entity.note, "preview")
                            : new TextView(entity.amount, entity.note, "preview");
                }
                public Row newEntity(Write dto) { throw new UnsupportedOperationException(); }
                public void applyUpdate(Row entity, Write dto) { entity.amount = dto.amount; entity.note = dto.note; }
                public Long extractId(Row entity) { return 1L; }
            };
        }
    }
}
