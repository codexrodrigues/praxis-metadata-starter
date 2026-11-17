package org.praxisplatform.uischema.id;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.bulk.BulkIdentityCodec;
import org.praxisplatform.uischema.bulk.BulkIdentityCodecs;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.exporting.CollectionExportFormat;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.exporting.CollectionExportResult;
import org.praxisplatform.uischema.exporting.CollectionExportScope;
import org.praxisplatform.uischema.exporting.CsvCollectionExportEngine;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Sort;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeIdSupportTest {

    @Embeddable
    public record EmploymentId(Long personId, Integer assignmentSeq) implements CompositeResourceId {
    }

    @Embeddable
    public record ItemTabelaId(String tab, String item, LocalDate validFrom) implements CompositeResourceId {
    }

    @Embeddable
    public static class LegacyClassId {
        private Long companyCode;
        private Integer branch;

        public LegacyClassId() {
        }

        public LegacyClassId(Long companyCode, Integer branch) {
            this.companyCode = companyCode;
            this.branch = branch;
        }

        public Long getCompanyCode() {
            return companyCode;
        }

        public void setCompanyCode(Long companyCode) {
            this.companyCode = companyCode;
        }

        public Integer getBranch() {
            return branch;
        }

        public void setBranch(Integer branch) {
            this.branch = branch;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LegacyClassId that)) return false;
            return Objects.equals(companyCode, that.companyCode) && Objects.equals(branch, that.branch);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyCode, branch);
        }
    }

    @Test
    void encodesAndDecodesRecordAndClassCompositeIds() {
        EmploymentId employmentId = new EmploymentId(1001L, 2);
        assertEquals("1001:2", employmentId.toWireId());
        assertEquals(employmentId, CompositeIdSupport.decode("1001:2", EmploymentId.class));
        assertEquals(List.of("personId", "assignmentSeq"), CompositeIdSupport.componentNames(EmploymentId.class));
        assertEquals(employmentId, CompositeIdSupport.fromComponentValues(EmploymentId.class, List.of(1001L, 2)));

        LegacyClassId classId = new LegacyClassId(10L, 3);
        assertEquals("10:3", CompositeIdSupport.encode(classId));
        assertEquals(classId, CompositeIdSupport.decode("10:3", LegacyClassId.class));
    }

    @Test
    void escapesColonsAndPercentsInStringComponents() {
        ItemTabelaId id = new ItemTabelaId("TAB:01", "A%B:C", LocalDate.of(2026, 1, 15));
        String wire = CompositeIdSupport.encode(id);
        assertEquals("TAB%3A01:A%25B%3AC:2026-01-15", wire);
        assertEquals(id, CompositeIdSupport.decode(wire, ItemTabelaId.class));
    }

    @Test
    void jacksonModuleSerializesToWireStringAndDeserializesFromStringOrObject() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new CompositeIdJacksonModule());

        EmploymentId id = new EmploymentId(5001L, 1);
        String json = mapper.writeValueAsString(id);
        assertEquals("\"5001:1\"", json);

        assertEquals(id, mapper.readValue("\"5001:1\"", EmploymentId.class));
        assertEquals(id, mapper.readValue("{\"personId\":5001,\"assignmentSeq\":1}", EmploymentId.class));

        OptionDTO<EmploymentId> option = new OptionDTO<>(id, "Employee 5001 / Assignment 1", null);
        String optionJson = mapper.writeValueAsString(option);
        assertTrue(optionJson.contains("\"id\":\"5001:1\""));
        OptionDTO<EmploymentId> decoded = mapper.readValue(optionJson, new TypeReference<>() {});
        assertEquals(id, decoded.id());
    }

    @Test
    void springConverterConvertsSingleAndCollectionParameters() {
        DefaultConversionService conversionService = new DefaultConversionService();
        conversionService.addConverter(new CompositeIdConverter());

        EmploymentId converted = conversionService.convert("9001:3", EmploymentId.class);
        assertEquals(new EmploymentId(9001L, 3), converted);
        assertEquals("9001:3", conversionService.convert(converted, String.class));

        @SuppressWarnings("unchecked")
        List<EmploymentId> list = (List<EmploymentId>) conversionService.convert(
                new String[]{"1001:1", "1001:2"},
                TypeDescriptor.valueOf(String[].class),
                TypeDescriptor.collection(List.class, TypeDescriptor.valueOf(EmploymentId.class))
        );
        assertNotNull(list);
        assertEquals(List.of(new EmploymentId(1001L, 1), new EmploymentId(1001L, 2)), list);
    }

    @Test
    void bulkIdentityCodecSupportsCompositeIds() {
        BulkIdentityCodec<String, EmploymentId> codec = BulkIdentityCodecs.composite(EmploymentId.class);
        EmploymentId id = new EmploymentId(777L, 4);
        assertEquals("777:4", codec.encode(id));
        assertEquals(id, codec.decode("777:4"));
        assertEquals("composite-id", codec.wireSchema().getFormat());
        assertThrows(IllegalArgumentException.class, () -> codec.decode("777"));
    }

    @Embeddable
    public record EnderecoValueObject(String logradouro, String complemento, String uf) {
    }

    static class SampleEntityWithEmbeddedId {
        @EmbeddedId
        private EmploymentId id;
    }

    @Test
    void distinguishesEmbeddableValueObjectsFromCompositeIdsAndRejectsNullComponents() throws Exception {
        assertFalse(CompositeIdSupport.isCompositeIdType(EnderecoValueObject.class));
        assertTrue(CompositeIdSupport.isEmbeddableValueType(EnderecoValueObject.class));

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new CompositeIdJacksonModule());

        EnderecoValueObject endereco = new EnderecoValueObject("Av. Paulista", null, "SP");
        String json = mapper.writeValueAsString(endereco);
        assertTrue(json.startsWith("{"), "Value objects must serialize as JSON objects, not composite-id strings: " + json);
        assertEquals(endereco, mapper.readValue(json, EnderecoValueObject.class));

        assertThrows(IllegalArgumentException.class, () ->
                CompositeIdSupport.fromComponentValues(EmploymentId.class, Arrays.asList(1001L, null)));
        assertThrows(JsonMappingException.class, () ->
                mapper.readValue("{\"personId\":1001}", EmploymentId.class));
    }

    @Test
    void expandsCompositeSortAndFormatsCompositeIdsInTabularExport() {
        Sort expanded = JpaCriteriaPathSupport.expandCompositeSort(
                SampleEntityWithEmbeddedId.class,
                Sort.by(Sort.Direction.DESC, "id")
        );
        List<Sort.Order> orders = expanded.toList();
        assertEquals(2, orders.size());
        assertEquals("id.personId", orders.get(0).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
        assertEquals("id.assignmentSeq", orders.get(1).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(1).getDirection());

        CsvCollectionExportEngine csvEngine = new CsvCollectionExportEngine();
        List<EmploymentId> rows = List.of(new EmploymentId(1001L, 1), new EmploymentId(2002L, 3));
        List<CollectionExportField> fields = List.of(
                new CollectionExportField("id", "Vínculo", true, true, "string", "id", null, null)
        );
        CollectionExportRequest<GenericFilterDTO> request = new CollectionExportRequest<>(
                "table",
                "vinculos-table",
                "/vinculos",
                CollectionExportFormat.CSV,
                CollectionExportScope.ALL,
                null,
                fields,
                null,
                null,
                null,
                Map.of(),
                true,
                false,
                null,
                "vinculos.csv",
                null,
                null,
                Map.of()
        );
        CollectionExportResult result = csvEngine.export(request, rows, fields, (row, field) -> row, Map.of());
        String csvText = new String(result.content(), StandardCharsets.UTF_8);
        assertTrue(csvText.contains("\"1001:1\""), csvText);
        assertTrue(csvText.contains("\"2002:3\""), csvText);
        assertFalse(csvText.contains("VinculoId["), csvText);
    }
}
