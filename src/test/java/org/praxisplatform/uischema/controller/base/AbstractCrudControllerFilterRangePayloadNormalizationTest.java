package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.web.FilterRequestBodyAdvice;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = AbstractCrudControllerFilterRangePayloadNormalizationTest.RangeController.class,
        properties = "praxis.pagination.max-size=20"
)
@Import({
        AbstractCrudControllerFilterRangePayloadNormalizationTest.RangeController.class,
        FilterRequestBodyAdvice.class,
        GlobalExceptionHandler.class
})
class AbstractCrudControllerFilterRangePayloadNormalizationTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RangeService service;

    @Test
    void shouldNormalizeMonetaryRangeObjectToList() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valorBetween": { "minPrice": 15000, "maxPrice": 6500 }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RangeFilterDTO> captor = ArgumentCaptor.forClass(RangeFilterDTO.class);
        verify(service).filterMappedWithIncludeIds(captor.capture(), any(Pageable.class), any(), any());
        RangeFilterDTO dto = captor.getValue();
        assertNotNull(dto.getValorBetween());
        assertEquals(2, dto.getValorBetween().size());
        assertEquals(0, dto.getValorBetween().get(0).compareTo(new BigDecimal("6500")));
        assertEquals(0, dto.getValorBetween().get(1).compareTo(new BigDecimal("15000")));
    }

    @Test
    void shouldNormalizeDateRangeObjectToList() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodo": { "startDate": "2026-01-05", "endDate": "2026-01-31" }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RangeFilterDTO> captor = ArgumentCaptor.forClass(RangeFilterDTO.class);
        verify(service).filterMappedWithIncludeIds(captor.capture(), any(Pageable.class), any(), any());
        RangeFilterDTO dto = captor.getValue();
        assertNotNull(dto.getPeriodo());
        assertEquals(2, dto.getPeriodo().size());
        assertEquals(LocalDate.of(2026, 1, 5), dto.getPeriodo().get(0));
        assertEquals(LocalDate.of(2026, 1, 31), dto.getPeriodo().get(1));
    }

    @Test
    void shouldPreserveUpperOnlyMonetaryRangeAsNullThenUpper() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valorBetween": { "maxPrice": 9999 }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RangeFilterDTO> captor = ArgumentCaptor.forClass(RangeFilterDTO.class);
        verify(service).filterMappedWithIncludeIds(captor.capture(), any(Pageable.class), any(), any());
        RangeFilterDTO dto = captor.getValue();
        assertNotNull(dto.getValorBetween());
        assertEquals(2, dto.getValorBetween().size());
        assertNull(dto.getValorBetween().get(0));
        assertEquals(0, dto.getValorBetween().get(1).compareTo(new BigDecimal("9999")));
    }

    @Test
    void shouldNormalizeMonetaryRelationAliasToRangeField() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valor": { "minPrice": 15000, "maxPrice": 6500 }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RangeFilterDTO> captor = ArgumentCaptor.forClass(RangeFilterDTO.class);
        verify(service).filterMappedWithIncludeIds(captor.capture(), any(Pageable.class), any(), any());
        RangeFilterDTO dto = captor.getValue();
        assertNotNull(dto.getValorBetween());
        assertEquals(2, dto.getValorBetween().size());
        assertEquals(0, dto.getValorBetween().get(0).compareTo(new BigDecimal("6500")));
        assertEquals(0, dto.getValorBetween().get(1).compareTo(new BigDecimal("15000")));
    }

    @Test
    void shouldNormalizeMonetarySplitAliasesToRangeField() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valorMin": null,
                                  "valorMax": 7200
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RangeFilterDTO> captor = ArgumentCaptor.forClass(RangeFilterDTO.class);
        verify(service).filterMappedWithIncludeIds(captor.capture(), any(Pageable.class), any(), any());
        RangeFilterDTO dto = captor.getValue();
        assertNotNull(dto.getValorBetween());
        assertEquals(2, dto.getValorBetween().size());
        assertNull(dto.getValorBetween().get(0));
        assertEquals(0, dto.getValorBetween().get(1).compareTo(new BigDecimal("7200")));
    }

    @Test
    void shouldPreserveUpperOnlyDateRangeAsNullThenUpper() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodo": { "endDate": "2026-01-31" }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RangeFilterDTO> captor = ArgumentCaptor.forClass(RangeFilterDTO.class);
        verify(service).filterMappedWithIncludeIds(captor.capture(), any(Pageable.class), any(), any());
        RangeFilterDTO dto = captor.getValue();
        assertNotNull(dto.getPeriodo());
        assertEquals(2, dto.getPeriodo().size());
        assertNull(dto.getPeriodo().get(0));
        assertEquals(LocalDate.of(2026, 1, 31), dto.getPeriodo().get(1));
    }

    @Test
    void shouldRejectRangeArrayWithMoreThanTwoBounds() throws Exception {
        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valorBetween": [100, 200, 300]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("failure"))
                .andExpect(jsonPath("$.message").value("Range payload accepts at most two bounds."));

        verify(service, never()).filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any());
    }

    @Test
    void shouldRejectConflictingRangeSourcesForSameField() throws Exception {
        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valorBetween": [100, 200],
                                  "valor": { "minPrice": 150, "maxPrice": 300 }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("failure"))
                .andExpect(jsonPath("$.message").value(
                        "Range payload for field 'valorBetween' provides conflicting sources. Use only one source."));

        verify(service, never()).filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any());
    }

    @Test
    void shouldRejectScalarRangePayloadWhenStrictContractIsEnabled() throws Exception {
        mockMvc.perform(post("/range/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valorBetween": 1500
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("failure"))
                .andExpect(jsonPath("$.message").value(
                        "Range payload escalar é inválido. Use [min], [null,max], [min,max] ou objeto canônico."));

        verify(service, never()).filterMappedWithIncludeIds(any(), any(Pageable.class), any(), any());
    }

    interface RangeService extends org.praxisplatform.uischema.service.base.BaseCrudService<
            RangeEntity, RangeDto, Long, RangeFilterDTO> {
        @Override
        default Optional<String> getDatasetVersion() {
            return Optional.of("1");
        }
    }

    static class RangeEntity {
        private Long id;

        RangeEntity() {
        }

        RangeEntity(Long id) {
            this.id = id;
        }

        Long getId() {
            return id;
        }
    }

    static class RangeDto {
        private Long id;

        RangeDto() {
        }

        RangeDto(Long id) {
            this.id = id;
        }

        Long getId() {
            return id;
        }
    }

    static class RangeFilterDTO implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "valor")
        private List<BigDecimal> valorBetween;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "data")
        private List<LocalDate> periodo;

        public List<BigDecimal> getValorBetween() {
            return valorBetween;
        }

        public void setValorBetween(List<BigDecimal> valorBetween) {
            this.valorBetween = valorBetween;
        }

        public List<LocalDate> getPeriodo() {
            return periodo;
        }

        public void setPeriodo(List<LocalDate> periodo) {
            this.periodo = periodo;
        }
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/range")
    static class RangeController extends AbstractCrudController<RangeEntity, RangeDto, Long, RangeFilterDTO> {
        @Autowired
        RangeService service;

        @Override
        protected RangeService getService() {
            return service;
        }

        @Override
        protected RangeDto toDto(RangeEntity entity) {
            return new RangeDto(entity.getId());
        }

        @Override
        protected RangeEntity toEntity(RangeDto dto) {
            return new RangeEntity(dto.getId());
        }

        @Override
        protected Long getEntityId(RangeEntity entity) {
            return entity.getId();
        }

        @Override
        protected Long getDtoId(RangeDto dto) {
            return dto.getId();
        }

        @Override
        protected String getBasePath() {
            return "/range";
        }
    }
}
