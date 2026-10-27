package org.praxisplatform.uischema.id.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.OptionLabel;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.id.CompositeResourceId;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupCapabilities;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = CompositeIdResourceE2ETest.CompositeFixtureApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "praxis.stats.enabled=true"
)
@ActiveProfiles("e2e-h2")
class CompositeIdResourceE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VinculoRepository vinculoRepository;

    @Autowired
    private FrequenciaRepository frequenciaRepository;

    @BeforeEach
    void setUp() {
        frequenciaRepository.deleteAll();
        vinculoRepository.deleteAll();

        VinculoEntity v1 = new VinculoEntity(
                new VinculoId(1001L, 1),
                "1001-1 - Maria Silva (Efetivo)",
                "ESTATUTARIO",
                new LotacaoEmbeddable("SEFAZ", "SP")
        );
        VinculoEntity v2 = new VinculoEntity(
                new VinculoId(1001L, 2),
                "1001-2 - Maria Silva (Comissionado)",
                "COMISSIONADO",
                new LotacaoEmbeddable("SEPLAN", "SP")
        );
        VinculoEntity v3 = new VinculoEntity(
                new VinculoId(2002L, 1),
                "2002-1 - João Souza (Efetivo)",
                "ESTATUTARIO",
                new LotacaoEmbeddable("SEDUC", "RJ")
        );
        vinculoRepository.saveAll(List.of(v1, v2, v3));

        FrequenciaEntity f1 = new FrequenciaEntity(v1, "FALTA INJUSTIFICADA", 2);
        FrequenciaEntity f2 = new FrequenciaEntity(v2, "LICENCA MEDICA", 5);
        FrequenciaEntity f3 = new FrequenciaEntity(v3, "FERIAS", 30);
        frequenciaRepository.saveAll(List.of(f1, f2, f3));
    }

    @Test
    void supportsFullCrudFilterOptionsStatsLookupAndOpenApiForCompositeIdResources() throws Exception {
        // 1. GET /{id} with composite wire ID "1001:1"
        ResponseEntity<String> getRes = get("/vinculos/1001:1");
        assertEquals(200, getRes.getStatusCode().value(), getRes.getBody());
        JsonNode getBody = body(getRes);
        assertEquals("1001:1", getBody.path("data").path("id").asText());
        assertEquals(1001L, getBody.path("data").path("numfunc").asLong());
        assertEquals(1, getBody.path("data").path("numvinc").asInt());
        assertEquals("SEFAZ", getBody.path("data").path("siglaSetor").asText());
        assertTrue(getBody.path("_links").path("self").path("href").asText().endsWith("/vinculos/1001:1"));

        // 2. POST /vinculos creating a new composite ID resource ("3003:1")
        ResponseEntity<String> createRes = postJson("/vinculos", """
                {
                  "numfunc": 3003,
                  "numvinc": 1,
                  "descricao": "3003-1 - Ana Costa (Efetivo)",
                  "regime": "ESTATUTARIO",
                  "siglaSetor": "SESAU",
                  "uf": "MG"
                }
                """);
        assertEquals(201, createRes.getStatusCode().value(), createRes.getBody());
        JsonNode createBody = body(createRes);
        assertEquals("3003:1", createBody.path("data").path("id").asText());

        // 3. PUT /vinculos/3003:1 updating composite ID resource
        ResponseEntity<String> updateRes = putJson("/vinculos/3003:1", """
                {
                  "descricao": "3003-1 - Ana Costa (Cedida)",
                  "regime": "CEDIDO",
                  "siglaSetor": "SEFAZ",
                  "uf": "MG"
                }
                """);
        assertEquals(200, updateRes.getStatusCode().value(), updateRes.getBody());
        assertEquals("CEDIDO", body(updateRes).path("data").path("regime").asText());

        // 4. POST /vinculos/filter by embedded ID component (id.numfunc) and @Embedded field (lotacao.uf)
        ResponseEntity<String> filterRes = postJson("/vinculos/filter?page=0&size=10", """
                {
                  "numfunc": 1001,
                  "uf": "SP"
                }
                """);
        assertEquals(200, filterRes.getStatusCode().value(), filterRes.getBody());
        JsonNode filterContent = body(filterRes).path("data").path("content");
        assertEquals(2, filterContent.size());

        // 5. POST /vinculos/filter with includeIds=2002:1 (composite wire ID hydration!)
        ResponseEntity<String> filterIncludeRes = postJson(
                "/vinculos/filter?page=0&size=10&includeIds=2002:1",
                """
                {
                  "numfunc": 1001
                }
                """
        );
        assertEquals(200, filterIncludeRes.getStatusCode().value(), filterIncludeRes.getBody());
        JsonNode includeContent = body(filterIncludeRes).path("data").path("content");
        assertEquals(3, includeContent.size());
        assertEquals("2002:1", includeContent.get(0).path("id").asText());

        // 6. POST /vinculos/options/filter & GET /vinculos/options/by-ids
        ResponseEntity<String> optionsFilterRes = postJson("/vinculos/options/filter?page=0&size=10", """
                {
                  "numfunc": 1001
                }
                """);
        assertEquals(200, optionsFilterRes.getStatusCode().value(), optionsFilterRes.getBody());
        JsonNode optionsContent = body(optionsFilterRes).path("content");
        assertEquals(2, optionsContent.size());
        assertEquals("1001:1", optionsContent.get(0).path("id").asText());

        ResponseEntity<String> optionsByIdsRes = get("/vinculos/options/by-ids?ids=1001:2&ids=1001:1");
        assertEquals(200, optionsByIdsRes.getStatusCode().value(), optionsByIdsRes.getBody());
        JsonNode optionsByIds = body(optionsByIdsRes);
        assertEquals(2, optionsByIds.size());
        assertEquals("1001:2", optionsByIds.get(0).path("id").asText());
        assertEquals("1001:1", optionsByIds.get(1).path("id").asText());

        // 7. RESOURCE_ENTITY OptionSource on @EmbeddedId entity (/frequencias/option-sources/vinculo/...)
        ResponseEntity<String> lookupFilterRes = postJson(
                "/frequencias/option-sources/vinculo/options/filter?search=Maria&page=0&size=10",
                "{}"
        );
        assertEquals(200, lookupFilterRes.getStatusCode().value(), lookupFilterRes.getBody());
        JsonNode lookupContent = body(lookupFilterRes).path("content");
        assertEquals(2, lookupContent.size());
        assertEquals("1001:1", lookupContent.get(0).path("id").asText());
        assertEquals("1001:1", lookupContent.get(0).path("extra").path("code").asText());
        assertEquals("ESTATUTARIO - SP", lookupContent.get(0).path("extra").path("description").asText());

        ResponseEntity<String> lookupByIdsRes = get(
                "/frequencias/option-sources/vinculo/options/by-ids?ids=2002:1&ids=1001:2"
        );
        assertEquals(200, lookupByIdsRes.getStatusCode().value(), lookupByIdsRes.getBody());
        JsonNode lookupByIds = body(lookupByIdsRes);
        assertEquals(2, lookupByIds.size());
        assertEquals("2002:1", lookupByIds.get(0).path("id").asText());
        assertEquals("1001:2", lookupByIds.get(1).path("id").asText());

        // 8. Child resource filtering by parent composite ID (@Filterable(relation = "vinculo.id"))
        ResponseEntity<String> childFilterRes = postJson("/frequencias/filter?page=0&size=10", """
                {
                  "vinculoId": "1001:2"
                }
                """);
        assertEquals(200, childFilterRes.getStatusCode().value(), childFilterRes.getBody());
        JsonNode childContent = body(childFilterRes).path("data").path("content");
        assertEquals(1, childContent.size());
        assertEquals("1001:2", childContent.get(0).path("vinculoId").asText());
        assertEquals("LICENCA MEDICA", childContent.get(0).path("ocorrencia").asText());

        // 9. Stats group-by on @Embedded field (lotacao.uf)
        ResponseEntity<String> statsRes = postJson("/vinculos/stats/group-by", """
                {
                  "filter": {},
                  "field": "uf",
                  "metrics": [
                    { "operation": "COUNT" }
                  ]
                }
                """);
        assertEquals(200, statsRes.getStatusCode().value(), statsRes.getBody());
        JsonNode buckets = body(statsRes).path("data").path("buckets");
        assertEquals(3, buckets.size());

        // 10. OpenAPI /schemas/filtered documents composite ID as type: "string", format: "composite-id"
        //     with x-ui.compositeIdComponents and x-ui.compositeIdSeparator
        ResponseEntity<String> schemaRes = get("/schemas/filtered?path=/vinculos/all&operation=get&schemaType=response");
        assertEquals(200, schemaRes.getStatusCode().value(), schemaRes.getBody());
        JsonNode idProp = body(schemaRes).path("properties").path("id");
        assertEquals("string", idProp.path("type").asText());
        assertEquals("composite-id", idProp.path("format").asText());
        assertEquals("numfunc", idProp.path("x-ui").path("compositeIdComponents").get(0).asText());
        assertEquals("numvinc", idProp.path("x-ui").path("compositeIdComponents").get(1).asText());
        assertEquals(":", idProp.path("x-ui").path("compositeIdSeparator").asText());

        // 11. GET /vinculos/by-ids preserves requested composite ID order
        ResponseEntity<String> byIdsRes = get("/vinculos/by-ids?ids=2002:1&ids=1001:1");
        assertEquals(200, byIdsRes.getStatusCode().value(), byIdsRes.getBody());
        JsonNode byIdsBody = body(byIdsRes);
        assertEquals(2, byIdsBody.size());
        assertEquals("2002:1", byIdsBody.get(0).path("id").asText());
        assertEquals("1001:1", byIdsBody.get(1).path("id").asText());

        // 12. POST /vinculos/filter?sort=id,desc expands composite ID sort to (id.numfunc DESC, id.numvinc DESC)
        ResponseEntity<String> sortedFilterRes = postJson("/vinculos/filter?page=0&size=10&sort=id,desc", "{}");
        assertEquals(200, sortedFilterRes.getStatusCode().value(), sortedFilterRes.getBody());
        JsonNode sortedContent = body(sortedFilterRes).path("data").path("content");
        assertEquals(4, sortedContent.size());
        assertEquals("3003:1", sortedContent.get(0).path("id").asText());
        assertEquals("2002:1", sortedContent.get(1).path("id").asText());
        assertEquals("1001:2", sortedContent.get(2).path("id").asText());
        assertEquals("1001:1", sortedContent.get(3).path("id").asText());

        // 13. GET /vinculos/1001:1/capabilities exposes canonical composite wire ID
        ResponseEntity<String> itemCapsRes = get("/vinculos/1001:1/capabilities");
        assertEquals(200, itemCapsRes.getStatusCode().value(), itemCapsRes.getBody());
        assertEquals("1001:1", body(itemCapsRes).path("resourceId").asText());

        // 14. DELETE /vinculos/batch deletes composite IDs via JSON array
        HttpHeaders batchHeaders = new HttpHeaders();
        batchHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> deleteBatchRes = rest.getRestTemplate().exchange(
                URI.create(url("/vinculos/batch")),
                HttpMethod.DELETE,
                new HttpEntity<>("[\"3003:1\"]", batchHeaders),
                String.class
        );
        assertEquals(204, deleteBatchRes.getStatusCode().value(), deleteBatchRes.getBody());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path) {
        return rest.getRestTemplate().getForEntity(URI.create(url(path)), String.class);
    }

    private ResponseEntity<String> postJson(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.getRestTemplate().postForEntity(URI.create(url(path)), new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> putJson(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.getRestTemplate().exchange(URI.create(url(path)), HttpMethod.PUT, new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    @SpringBootApplication(exclude = SecurityAutoConfiguration.class)
    @EntityScan(basePackageClasses = CompositeIdResourceE2ETest.class)
    @ComponentScan(basePackageClasses = CompositeIdResourceE2ETest.class)
    @EnableJpaRepositories(basePackageClasses = CompositeIdResourceE2ETest.class, considerNestedRepositories = true)
    static class CompositeFixtureApp {

        @Bean
        OptionSourceRegistry compositeOptionSourceRegistry() {
            return OptionSourceRegistry.builder()
                    .add(FrequenciaEntity.class, new OptionSourceDescriptor(
                            "vinculo",
                            OptionSourceType.RESOURCE_ENTITY,
                            "/frequencias",
                            "vinculoId",
                            "vinculo",
                            "vinculo.descricao",
                            "vinculo.id",
                            List.of(),
                            Map.of(),
                            OptionSourcePolicy.defaults(),
                            new EntityLookupDescriptor(
                                    "ergon.vinculos",
                                    "vinculo.id",
                                    List.of("vinculo.regime", "vinculo.lotacao.uf"),
                                    null,
                                    null,
                                    null,
                                    List.of("vinculo.descricao", "vinculo.regime", "vinculo.lotacao.siglaSetor"),
                                    Map.of(),
                                    null,
                                    LookupCapabilities.defaults(),
                                    null
                            )
                    ))
                    .build();
        }
    }

    @Embeddable
    public record VinculoId(
            @Column(name = "numfunc", nullable = false) Long numfunc,
            @Column(name = "numvinc", nullable = false) Integer numvinc
    ) implements CompositeResourceId {
    }

    @Embeddable
    public static class LotacaoEmbeddable {
        @Column(name = "sigla_setor", length = 30)
        private String siglaSetor;

        @Column(name = "uf", length = 2)
        private String uf;

        public LotacaoEmbeddable() {
        }

        public LotacaoEmbeddable(String siglaSetor, String uf) {
            this.siglaSetor = siglaSetor;
            this.uf = uf;
        }

        public String getSiglaSetor() {
            return siglaSetor;
        }

        public void setSiglaSetor(String siglaSetor) {
            this.siglaSetor = siglaSetor;
        }

        public String getUf() {
            return uf;
        }

        public void setUf(String uf) {
            this.uf = uf;
        }
    }

    @Entity
    @Table(name = "ergon_vinculos_test")
    public static class VinculoEntity {

        @EmbeddedId
        private VinculoId id;

        @OptionLabel
        @Column(nullable = false, length = 160)
        private String descricao;

        @Column(nullable = false, length = 40)
        private String regime;

        @Embedded
        private LotacaoEmbeddable lotacao;

        public VinculoEntity() {
        }

        public VinculoEntity(VinculoId id, String descricao, String regime, LotacaoEmbeddable lotacao) {
            this.id = id;
            this.descricao = descricao;
            this.regime = regime;
            this.lotacao = lotacao;
        }

        public VinculoId getId() {
            return id;
        }

        public void setId(VinculoId id) {
            this.id = id;
        }

        public String getDescricao() {
            return descricao;
        }

        public void setDescricao(String descricao) {
            this.descricao = descricao;
        }

        public String getRegime() {
            return regime;
        }

        public void setRegime(String regime) {
            this.regime = regime;
        }

        public LotacaoEmbeddable getLotacao() {
            return lotacao;
        }

        public void setLotacao(LotacaoEmbeddable lotacao) {
            this.lotacao = lotacao;
        }
    }

    @Entity
    @Table(name = "ergon_frequencias_test")
    public static class FrequenciaEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumns({
                @JoinColumn(name = "numfunc", referencedColumnName = "numfunc", nullable = false),
                @JoinColumn(name = "numvinc", referencedColumnName = "numvinc", nullable = false)
        })
        private VinculoEntity vinculo;

        @OptionLabel
        @Column(nullable = false, length = 80)
        private String ocorrencia;

        @Column(nullable = false)
        private Integer dias;

        public FrequenciaEntity() {
        }

        public FrequenciaEntity(VinculoEntity vinculo, String ocorrencia, Integer dias) {
            this.vinculo = vinculo;
            this.ocorrencia = ocorrencia;
            this.dias = dias;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public VinculoEntity getVinculo() {
            return vinculo;
        }

        public void setVinculo(VinculoEntity vinculo) {
            this.vinculo = vinculo;
        }

        public String getOcorrencia() {
            return ocorrencia;
        }

        public void setOcorrencia(String ocorrencia) {
            this.ocorrencia = ocorrencia;
        }

        public Integer getDias() {
            return dias;
        }

        public void setDias(Integer dias) {
            this.dias = dias;
        }
    }

    @Repository
    public interface VinculoRepository extends BaseCrudRepository<VinculoEntity, VinculoId> {
    }

    @Repository
    public interface FrequenciaRepository extends BaseCrudRepository<FrequenciaEntity, Long> {
    }

    public record VinculoResponseDTO(
            @Schema(description = "Identificador funcional composto do vínculo (numfunc:numvinc)", example = "1001:1")
            @UISchema(label = "ID do Vínculo", readOnly = true)
            VinculoId id,
            @UISchema(label = "Número Funcional", type = FieldDataType.NUMBER)
            Long numfunc,
            @UISchema(label = "Número do Vínculo", type = FieldDataType.NUMBER)
            Integer numvinc,
            @UISchema(label = "Descrição", type = FieldDataType.TEXT)
            String descricao,
            @UISchema(label = "Regime Jurídico", type = FieldDataType.TEXT)
            String regime,
            @UISchema(label = "Setor", type = FieldDataType.TEXT)
            String siglaSetor,
            @UISchema(label = "UF", type = FieldDataType.TEXT)
            String uf
    ) {
    }

    public record CreateVinculoDTO(
            Long numfunc,
            Integer numvinc,
            String descricao,
            String regime,
            String siglaSetor,
            String uf
    ) {
    }

    public record UpdateVinculoDTO(
            String descricao,
            String regime,
            String siglaSetor,
            String uf
    ) {
    }

    public static class VinculoFilterDTO implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "id.numfunc")
        private Long numfunc;

        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "id.numvinc")
        private Integer numvinc;

        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "lotacao.uf")
        private String uf;

        public Long getNumfunc() {
            return numfunc;
        }

        public void setNumfunc(Long numfunc) {
            this.numfunc = numfunc;
        }

        public Integer getNumvinc() {
            return numvinc;
        }

        public void setNumvinc(Integer numvinc) {
            this.numvinc = numvinc;
        }

        public String getUf() {
            return uf;
        }

        public void setUf(String uf) {
            this.uf = uf;
        }
    }

    @Service
    public static class VinculoService extends AbstractBaseResourceService<
            VinculoEntity,
            VinculoResponseDTO,
            VinculoId,
            VinculoFilterDTO,
            CreateVinculoDTO,
            UpdateVinculoDTO> {

        private static final ResourceMapper<VinculoEntity, VinculoResponseDTO, CreateVinculoDTO, UpdateVinculoDTO, VinculoId> MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public VinculoResponseDTO toResponse(VinculoEntity entity) {
                        LotacaoEmbeddable lot = entity.getLotacao();
                        return new VinculoResponseDTO(
                                entity.getId(),
                                entity.getId().numfunc(),
                                entity.getId().numvinc(),
                                entity.getDescricao(),
                                entity.getRegime(),
                                lot != null ? lot.getSiglaSetor() : null,
                                lot != null ? lot.getUf() : null
                        );
                    }

                    @Override
                    public VinculoEntity newEntity(CreateVinculoDTO dto) {
                        return new VinculoEntity(
                                new VinculoId(dto.numfunc(), dto.numvinc()),
                                dto.descricao(),
                                dto.regime(),
                                new LotacaoEmbeddable(dto.siglaSetor(), dto.uf())
                        );
                    }

                    @Override
                    public void applyUpdate(VinculoEntity entity, UpdateVinculoDTO dto) {
                        entity.setDescricao(dto.descricao());
                        entity.setRegime(dto.regime());
                        entity.setLotacao(new LotacaoEmbeddable(dto.siglaSetor(), dto.uf()));
                    }

                    @Override
                    public VinculoId extractId(VinculoEntity entity) {
                        return entity.getId();
                    }
                };

        public VinculoService(VinculoRepository repository) {
            super(repository, VinculoEntity.class);
        }

        @Override
        public StatsSupportMode getGroupByStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsFieldRegistry getStatsFieldRegistry() {
            return StatsFieldRegistry.builder()
                    .categoricalGroupByBucket("uf", "lotacao.uf")
                    .categoricalGroupByBucket("regime", "regime")
                    .build();
        }

        @Override
        protected ResourceMapper<VinculoEntity, VinculoResponseDTO, CreateVinculoDTO, UpdateVinculoDTO, VinculoId> getResourceMapper() {
            return MAPPER;
        }
    }

    @ApiResource(value = "/vinculos", resourceKey = "ergon.vinculos")
    public static class VinculoController extends AbstractResourceController<
            VinculoResponseDTO,
            VinculoId,
            VinculoFilterDTO,
            CreateVinculoDTO,
            UpdateVinculoDTO> {

        private final VinculoService service;

        public VinculoController(VinculoService service) {
            this.service = service;
        }

        @Override
        protected VinculoService getService() {
            return service;
        }

        @Override
        protected VinculoId getResponseId(VinculoResponseDTO dto) {
            return dto.id();
        }
    }

    public record FrequenciaResponseDTO(
            Long id,
            VinculoId vinculoId,
            String vinculoDescricao,
            String ocorrencia,
            Integer dias
    ) {
    }

    public record CreateFrequenciaDTO(
            VinculoId vinculoId,
            String ocorrencia,
            Integer dias
    ) {
    }

    public record UpdateFrequenciaDTO(
            String ocorrencia,
            Integer dias
    ) {
    }

    public static class FrequenciaFilterDTO implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "vinculo.id")
        @UISchema(controlType = FieldControlType.SELECT)
        private VinculoId vinculoId;

        public VinculoId getVinculoId() {
            return vinculoId;
        }

        public void setVinculoId(VinculoId vinculoId) {
            this.vinculoId = vinculoId;
        }
    }

    @Service
    public static class FrequenciaService extends AbstractBaseResourceService<
            FrequenciaEntity,
            FrequenciaResponseDTO,
            Long,
            FrequenciaFilterDTO,
            CreateFrequenciaDTO,
            UpdateFrequenciaDTO> {

        private final VinculoRepository vinculoRepository;

        public FrequenciaService(FrequenciaRepository repository, VinculoRepository vinculoRepository) {
            super(repository, FrequenciaEntity.class);
            this.vinculoRepository = vinculoRepository;
        }

        @Override
        protected ResourceMapper<FrequenciaEntity, FrequenciaResponseDTO, CreateFrequenciaDTO, UpdateFrequenciaDTO, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                @Override
                public FrequenciaResponseDTO toResponse(FrequenciaEntity entity) {
                    return new FrequenciaResponseDTO(
                            entity.getId(),
                            entity.getVinculo().getId(),
                            entity.getVinculo().getDescricao(),
                            entity.getOcorrencia(),
                            entity.getDias()
                    );
                }

                @Override
                public FrequenciaEntity newEntity(CreateFrequenciaDTO dto) {
                    VinculoEntity vinculo = vinculoRepository.findById(dto.vinculoId()).orElseThrow();
                    return new FrequenciaEntity(vinculo, dto.ocorrencia(), dto.dias());
                }

                @Override
                public void applyUpdate(FrequenciaEntity entity, UpdateFrequenciaDTO dto) {
                    entity.setOcorrencia(dto.ocorrencia());
                    entity.setDias(dto.dias());
                }

                @Override
                public Long extractId(FrequenciaEntity entity) {
                    return entity.getId();
                }
            };
        }
    }

    @ApiResource(value = "/frequencias", resourceKey = "ergon.frequencias")
    public static class FrequenciaController extends AbstractResourceController<
            FrequenciaResponseDTO,
            Long,
            FrequenciaFilterDTO,
            CreateFrequenciaDTO,
            UpdateFrequenciaDTO> {

        private final FrequenciaService service;

        public FrequenciaController(FrequenciaService service) {
            this.service = service;
        }

        @Override
        protected FrequenciaService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(FrequenciaResponseDTO dto) {
            return dto.id();
        }
    }
}
