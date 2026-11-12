package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.FilterCriterion;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiResourceQuickFilterResolverTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvesQuickFiltersFromCanonicalApiResource() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method method = FixtureController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new FixtureController(), method);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/fixture").build(), handlerMethod));

        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        List<Map<String, Object>> filters = resolver.resolve("/api/items/");
        assertEquals(3, filters.size());

        Map<String, Object> f0 = filters.get(0);
        assertEquals("active", f0.get("id"));
        assertEquals("Ativos", f0.get("label"));
        assertEquals("check_circle", f0.get("icon"));
        assertEquals(Map.of("ativo", true), f0.get("filter"));

        Map<String, Object> f1 = filters.get(1);
        assertEquals("priority", f1.get("id"));
        assertEquals("Alta Prioridade", f1.get("label"));
        assertEquals(Map.of("nivel", "ALTO", "urgente", true), f1.get("filter"));

        Map<String, Object> f2 = filters.get(2);
        assertEquals("json", f2.get("id"));
        assertEquals("JSON format", f2.get("label"));
        Map<String, Object> jsonFilter = (Map<String, Object>) f2.get("filter");
        assertEquals("TI", jsonFilter.get("categoria"));
        assertEquals(10, jsonFilter.get("pontos"));

        assertEquals(
                Map.of("statuses", List.of("ACTIVE", "PENDING"), "ids", List.of(10, 20)),
                resolver.parseFilterExpression("statuses=ACTIVE,PENDING&ids=10&ids=20")
        );
    }

    @Test
    void ignoresResourcesWithoutQuickFilters() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method method = UnconfiguredController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new UnconfiguredController(), method);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/unconfigured").build(), handlerMethod));

        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        assertTrue(resolver.resolve("/api/unconfigured").isEmpty());
    }

    @Test
    void handlesBlankOrNullResourcePath() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve("   ").isEmpty());
    }

    @Test
    void usesCanonicalMvcHandlerMappingWhenAdditionalMappingsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
            context.registerBean("controllerEndpointHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
            context.registerBean(ApiResourceQuickFilterResolver.class);

            context.refresh();

            assertTrue(context.containsBean("apiResourceQuickFilterResolver"));
        }
    }

    @ApiResource(
            value = "/api/items",
            resourceKey = "test.items",
            quickFilters = {
                    @QuickFilter(id = "active", label = "Ativos", filter = "ativo=true", icon = "check_circle"),
                    @QuickFilter(id = "priority", label = "Alta Prioridade", filter = "nivel=ALTO&urgente=true"),
                    @QuickFilter(id = "json", label = "JSON format", filter = "{\"categoria\":\"TI\",\"pontos\":10}")
            }
    )
    static class FixtureController {
        void list() { }
    }

    @ApiResource(value = "/api/unconfigured", resourceKey = "test.unconfigured")
    static class UnconfiguredController {
        void list() { }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesTypedCriteriaFilterDtoCoercionAndProgrammaticDescriptors() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        TypedRetirementController controllerBean = new TypedRetirementController();
        Method method = TypedRetirementController.class.getDeclaredMethod("ping");
        HandlerMethod handlerMethod = new HandlerMethod(controllerBean, method);
        when(handlerMapping.getHandlerMethods())
                .thenReturn(Map.of(RequestMappingInfo.paths("/api/retirements").build(), handlerMethod));

        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        List<Map<String, Object>> filters = resolver.resolve("/api/retirements");
        assertEquals(4, filters.size());

        // order = -10 programmatic comes first
        Map<String, Object> f0 = filters.get(0);
        assertEquals("programmatic-comprev", f0.get("id"));
        assertEquals("Elegíveis COMPREV", f0.get("label"));
        assertEquals("info", f0.get("tone"));
        assertEquals(-10, f0.get("order"));
        Map<String, Object> f0Filter = (Map<String, Object>) f0.get("filter");
        assertEquals(Boolean.TRUE, f0Filter.get("apenasComprev"));
        assertEquals("001", f0Filter.get("codigoSetor"));

        // single-item values on List<String> field MUST emit JSON array ["HOMOLOGADO"], and "001" on String stays String "001"
        Map<String, Object> f1 = filters.get(1);
        assertEquals("ativas-folha", f1.get("id"));
        assertEquals("success", f1.get("tone"));
        assertEquals(Boolean.TRUE, f1.get("isDefault"));
        assertEquals("Concessões vigentes em folha.", f1.get("description"));
        Map<String, Object> f1Filter = (Map<String, Object>) f1.get("filter");
        assertEquals(List.of("HOMOLOGADO"), f1Filter.get("situacoesIn"));
        assertEquals(Boolean.TRUE, f1Filter.get("apenasVigentes"));
        assertEquals("001", f1Filter.get("codigoSetor"));

        // multi-item values + integer + decimal + ISO LocalDate
        Map<String, Object> f2 = filters.get(2);
        assertEquals("revisao-90d", f2.get("id"));
        assertEquals("danger", f2.get("tone"));
        assertNull(f2.get("isDefault"));
        Map<String, Object> f2Filter = (Map<String, Object>) f2.get("filter");
        assertEquals(90, f2Filter.get("revisaoProximosDias"));
        assertEquals(new BigDecimal("1500.5"), f2Filter.get("abateTetoMinimo"));
        assertEquals("2026-01-15", f2Filter.get("dataCorte"));

        // bool = false explicit
        Map<String, Object> f3 = filters.get(3);
        assertEquals("encerradas", f3.get("id"));
        assertEquals(Map.of("apenasVigentes", Boolean.FALSE), f3.get("filter"));

        // resolveByResourceKey returns identical list
        assertEquals(filters, resolver.resolveByResourceKey("test.retirements"));
    }

    enum SampleStage {
        HOMOLOGADO,
        PENDENTE
    }

    public static class RetirementFilterDTO implements GenericFilterDTO {
        @Filterable
        private List<SampleStage> situacoesIn;
        @Filterable
        private Boolean apenasVigentes;
        @Filterable
        private Boolean apenasComprev;
        @Filterable
        private String codigoSetor;
        @Filterable
        private Integer revisaoProximosDias;
        @Filterable
        private BigDecimal abateTetoMinimo;
        @Filterable
        private LocalDate dataCorte;

        public List<SampleStage> getSituacoesIn() { return situacoesIn; }
        public void setSituacoesIn(List<SampleStage> situacoesIn) { this.situacoesIn = situacoesIn; }
        public Boolean getApenasVigentes() { return apenasVigentes; }
        public void setApenasVigentes(Boolean apenasVigentes) { this.apenasVigentes = apenasVigentes; }
        public Boolean getApenasComprev() { return apenasComprev; }
        public void setApenasComprev(Boolean apenasComprev) { this.apenasComprev = apenasComprev; }
        public String getCodigoSetor() { return codigoSetor; }
        public void setCodigoSetor(String codigoSetor) { this.codigoSetor = codigoSetor; }
        public Integer getRevisaoProximosDias() { return revisaoProximosDias; }
        public void setRevisaoProximosDias(Integer revisaoProximosDias) { this.revisaoProximosDias = revisaoProximosDias; }
        public BigDecimal getAbateTetoMinimo() { return abateTetoMinimo; }
        public void setAbateTetoMinimo(BigDecimal abateTetoMinimo) { this.abateTetoMinimo = abateTetoMinimo; }
        public LocalDate getDataCorte() { return dataCorte; }
        public void setDataCorte(LocalDate dataCorte) { this.dataCorte = dataCorte; }
    }

    @ApiResource(
            value = "/api/retirements",
            resourceKey = "test.retirements",
            quickFilters = {
                    @QuickFilter(
                            id = "ativas-folha",
                            label = "Ativas em folha",
                            icon = "check_circle",
                            tone = QuickFilterTone.SUCCESS,
                            defaultActive = true,
                            description = "Concessões vigentes em folha.",
                            criteria = {
                                    @FilterCriterion(field = "situacoesIn", values = "HOMOLOGADO"),
                                    @FilterCriterion(field = "apenasVigentes", bool = true),
                                    @FilterCriterion(field = "codigoSetor", values = "001")
                            }
                    ),
                    @QuickFilter(
                            id = "revisao-90d",
                            label = "Revisão 90d",
                            tone = QuickFilterTone.DANGER,
                            criteria = {
                                    @FilterCriterion(field = "revisaoProximosDias", number = 90),
                                    @FilterCriterion(field = "abateTetoMinimo", decimal = 1500.5),
                                    @FilterCriterion(field = "dataCorte", values = "2026-01-15")
                            }
                    ),
                    @QuickFilter(
                            id = "encerradas",
                            label = "Encerradas",
                            criteria = @FilterCriterion(field = "apenasVigentes", bool = false)
                    )
            }
    )
    static class TypedRetirementController extends AbstractReadOnlyResourceController<Map<String, Object>, Long, RetirementFilterDTO> {
        @Override
        protected BaseResourceQueryService<Map<String, Object>, Long, RetirementFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(Map<String, Object> dto) {
            return null;
        }

        @Override
        protected List<QuickFilterDescriptor<RetirementFilterDTO>> defineQuickFilters() {
            return List.of(
                    QuickFilterDescriptor.<RetirementFilterDTO>builder("programmatic-comprev", "Elegíveis COMPREV")
                            .tone(QuickFilterTone.INFO)
                            .order(-10)
                            .where(f -> {
                                f.setApenasComprev(true);
                                f.setCodigoSetor("001");
                            })
                            .build()
            );
        }

        void ping() { }
    }
}
