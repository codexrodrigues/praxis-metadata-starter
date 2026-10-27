package org.praxisplatform.uischema.customfields.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.uischema.testsupport.ResourceContractNeonFixture;
import org.praxisplatform.uischema.testsupport.ResourceContractNeonTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;
import org.praxisplatform.uischema.customfields.*;
import org.praxisplatform.uischema.exporting.*;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
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
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + PostgreSQL proof. Requires an exclusively assigned Neon branch/database. */
@SpringBootTest(classes = CustomFieldsResourceE2ETest.FixtureApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e-neon")
@EnabledIfEnvironmentVariable(named = "PRAXIS_BULK_NEON_CONNECTION_FILE", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceContractNeonTest
class CustomFieldsResourceE2ETest {
    static final String RESOURCE = "custom.employees";
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired EmployeeRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static ResourceContractNeonFixture fixture;

    @DynamicPropertySource
    static void requireAssignedRemoteDatabase(DynamicPropertyRegistry properties) {
        fixture = ResourceContractNeonFixture.open(CustomFieldsResourceE2ETest.class, "custom-fields");
        fixture.bind(properties);
    }

    @BeforeEach
    void verifyIdentityAndResetOnlyFixtureData() {
        fixture.verifyRuntime();
        repository.deleteAll();
    }

    @Test
    void recordDtosRoundTripValidateBeforeWritingAndFilterNativeJsonb() throws Exception {
        ResponseEntity<String> created = request("/custom-employees", HttpMethod.POST, "org-a", """
                {"name":"Ana","customFields":{"numero_calcado":42}}
                """, null);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        long id = body(created).path("data").path("id").asLong();
        assertTrue(id > 0);
        assertEquals(42, body(created).path("data").path("customFields").path("numero_calcado").asInt());
        ResponseEntity<String> read = request("/custom-employees/" + id, HttpMethod.GET, "org-a", null, null);
        assertEquals(200, read.getStatusCode().value(), read.getBody());
        assertEquals("Ana", body(read).path("data").path("name").asText());
        ResponseEntity<String> updated = request("/custom-employees/" + id, HttpMethod.PUT, "org-a", """
                {"name":"Ana Maria","customFields":{"numero_calcado":43}}
                """, null);
        assertEquals(200, updated.getStatusCode().value(), updated.getBody());
        assertEquals(43, body(updated).path("data").path("customFields").path("numero_calcado").asInt());

        for (String invalid : List.of("{}", "{\"numero_calcado\":\"wrong\"}", "{\"numero_calcado\":99}",
                "{\"numero_calcado\":43,\"undeclared\":true}")) {
            ResponseEntity<String> rejected = request("/custom-employees/" + id, HttpMethod.PUT, "org-a",
                    "{\"name\":\"Must not be saved\",\"customFields\":" + invalid + "}", null);
            assertTrue(List.of(400, 422).contains(rejected.getStatusCode().value()), rejected.getBody());
            assertTrue(rejected.getBody().contains("customFields."), rejected.getBody());
            assertEquals("Ana Maria", repository.findById(id).orElseThrow().getName());
        }
        ResponseEntity<String> unknownCreate = request("/custom-employees", HttpMethod.POST, "org-a",
                "{\"name\":\"Bad\",\"customFields\":{\"numero_calcado\":43,\"rogue\":1}}", null);
        assertTrue(List.of(400, 422).contains(unknownCreate.getStatusCode().value()), unknownCreate.getBody());
        assertTrue(unknownCreate.getBody().contains("customFields.rogue"), unknownCreate.getBody());
        assertEquals(1, repository.count());

        assertEquals(201, request("/custom-employees", HttpMethod.POST, "org-a",
                "{\"name\":\"Bia\",\"customFields\":{\"numero_calcado\":38}}", null).getStatusCode().value());
        ResponseEntity<String> filter = request("/custom-employees/filter?page=0&size=10", HttpMethod.POST,
                "org-a", "{\"customFields\":{\"numero_calcado\":43}}", null);
        assertEquals(200, filter.getStatusCode().value(), filter.getBody());
        JsonNode rows = body(filter).path("data").path("content");
        assertEquals(1, rows.size(), filter.getBody());
        assertEquals(id, rows.get(0).path("id").asLong());
        ResponseEntity<String> unknownFilter = request("/custom-employees/filter", HttpMethod.POST,
                "org-a", "{\"customFields\":{\"rogue\":43}}", null);
        assertEquals(400, unknownFilter.getStatusCode().value(), unknownFilter.getBody());
        assertEquals(204, request("/custom-employees/" + id, HttpMethod.DELETE, "org-a", null, null).getStatusCode().value());
        assertFalse(repository.existsById(id));
    }

    @Test
    void tenantSchemasHaveIndependentHashesAndConditionalRequests() throws Exception {
        String path = "/schemas/filtered?path=/custom-employees&operation=post&schemaType=request";
        ResponseEntity<String> a = request(path, HttpMethod.GET, "org-a", null, null);
        ResponseEntity<String> b = request(path, HttpMethod.GET, "org-b", null, null);
        assertEquals(200, a.getStatusCode().value(), a.getBody());
        assertEquals(200, b.getStatusCode().value(), b.getBody());
        JsonNode aContainer = body(a).path("properties").path("customFields");
        assertTrue(aContainer.path("x-ui").path("customFieldsContainer").asBoolean());
        assertEquals("numero_calcado", aContainer.path("required").get(0).asText());
        assertEquals("integer", aContainer.path("properties").path("numero_calcado").path("type").asText());
        assertEquals(50, aContainer.path("properties").path("numero_calcado").path("maximum").asInt());
        assertTrue(aContainer.path("properties").path("numero_calcado").path("x-ui").path("customField").asBoolean());
        JsonNode bFields = body(b).path("properties").path("customFields").path("properties");
        assertTrue(bFields.has("matricula"));
        assertFalse(bFields.has("numero_calcado"));
        assertNotNull(a.getHeaders().getETag());
        assertNotEquals(a.getHeaders().getETag(), b.getHeaders().getETag());
        assertTrue(a.getHeaders().getVary().containsAll(List.of("Accept-Encoding", "X-Tenant", "X-Tenant-ID")));
        ResponseEntity<String> unchanged = request(path, HttpMethod.GET, "org-a", null, a.getHeaders().getETag());
        assertEquals(304, unchanged.getStatusCode().value());
        assertEquals(a.getHeaders().getETag(), unchanged.getHeaders().getETag());
        assertTrue(unchanged.getHeaders().getVary().containsAll(List.of("X-Tenant", "X-Tenant-ID")));
        ResponseEntity<String> crossTenant = request(path, HttpMethod.GET, "org-b", null, a.getHeaders().getETag());
        assertEquals(200, crossTenant.getStatusCode().value(), crossTenant.getBody());
        assertEquals(b.getHeaders().getETag(), crossTenant.getHeaders().getETag());
        ResponseEntity<String> noDefinitions = request(path, HttpMethod.GET, "org-empty", null, null);
        JsonNode emptyContainer = body(noDefinitions).path("properties").path("customFields");
        assertTrue(emptyContainer.isMissingNode() || emptyContainer.path("x-ui").path("hidden").asBoolean());
        HttpHeaders alias = new HttpHeaders();
        alias.set("X-Tenant-ID", "org-a");
        ResponseEntity<String> viaAlias = rest.exchange(URI.create(url(path)), HttpMethod.GET, new HttpEntity<>(alias), String.class);
        assertEquals(a.getHeaders().getETag(), viaAlias.getHeaders().getETag());
    }

    @Test
    void exportsCanonicalCustomFieldPathsInCsvAndJson() throws Exception {
        assertEquals(201, request("/custom-employees", HttpMethod.POST, "org-a",
                "{\"name\":\"Ana\",\"customFields\":{\"numero_calcado\":42}}", null).getStatusCode().value());
        for (String format : List.of("csv", "json")) {
            ResponseEntity<String> exported = request("/custom-employees/export", HttpMethod.POST, "org-a",
                    "{\"format\":\"" + format + "\",\"fields\":[{\"key\":\"customFields.numero_calcado\"}]}", null);
            assertEquals(200, exported.getStatusCode().value(), exported.getBody());
            assertEquals("1", exported.getHeaders().getFirst("X-Export-Row-Count"));
            if (format.equals("json")) {
                assertEquals(42, body(exported).get(0).path("customFields.numero_calcado").asInt(), exported.getBody());
            } else {
                assertTrue(exported.getBody().contains("Shoe size"), exported.getBody());
                assertTrue(exported.getBody().contains("42"), exported.getBody());
            }
        }
    }

    @Test
    void updatePreservesServerOwnedValuesAndRejectsAssignments() throws Exception {
        var created = request("/custom-employees", HttpMethod.POST, "org-a",
                "{\"name\":\"Ana\",\"customFields\":{\"numero_calcado\":42}}", null);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        long id = body(created).at("/data/id").asLong();
        assertTrue(body(created).at("/data/customFields/auditFlag").asBoolean());
        var updated = request("/custom-employees/" + id, HttpMethod.PUT, "org-a",
                "{\"name\":\"Ana Maria\",\"customFields\":{\"numero_calcado\":43}}", null);
        assertEquals(200, updated.getStatusCode().value(), updated.getBody());
        assertTrue(body(updated).at("/data/customFields/auditFlag").asBoolean());
        assertEquals(Boolean.TRUE, repository.findById(id).orElseThrow().customFields.get("auditFlag"));
        var rejected = request("/custom-employees/" + id, HttpMethod.PUT, "org-a",
                "{\"name\":\"Bad\",\"customFields\":{\"numero_calcado\":44,\"auditFlag\":false}}", null);
        assertEquals(400, rejected.getStatusCode().value(), rejected.getBody());
        assertTrue(rejected.getBody().contains("customFields.auditFlag"));
        var persisted = repository.findById(id).orElseThrow();
        assertEquals("Ana Maria", persisted.name);
        assertEquals(Boolean.TRUE, persisted.customFields.get("auditFlag"));
    }

    @Test
    void publishedFilterAndWriteSchemasMatchRealHttpPayloads() throws Exception {
        String filterUrl = "/schemas/filtered?path=/custom-employees/filter&operation=post&schemaType=request";
        var projected = request(filterUrl, HttpMethod.GET, "org-ops", null, null);
        assertEquals(200, projected.getStatusCode().value(), projected.getBody());
        var filterSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(body(projected));
        for (String payload : List.of("{}", "{\"customFields\":{}}",
                "{\"customFields\":{\"age\":[42],\"score\":true}}")) {
            assertTrue(filterSchema.validate(json.readTree(payload)).isEmpty(), payload);
            var response = request("/custom-employees/filter", HttpMethod.POST, "org-ops", payload, null);
            assertEquals(200, response.getStatusCode().value(), response.getBody());
        }
        for (String payload : List.of("{\"customFields\":{\"age\":42}}", "{\"customFields\":{\"score\":2}}")) {
            assertFalse(filterSchema.validate(json.readTree(payload)).isEmpty(), payload);
            assertEquals(400, request("/custom-employees/filter", HttpMethod.POST, "org-ops", payload, null).getStatusCode().value());
        }
        repository.saveAndFlush(new Employee("Selected", Map.of("age", 42)));
        repository.saveAndFlush(new Employee("Excluded", Map.of("age", 43, "score", 7)));
        var selected = request("/custom-employees/filter", HttpMethod.POST, "org-ops",
                "{\"customFields\":{\"age\":[42],\"score\":true}}", null);
        assertEquals(200, selected.getStatusCode().value(), selected.getBody());
        assertEquals(1, body(selected).at("/data/content").size());
        assertEquals("Selected", body(selected).at("/data/content/0/name").asText());

        String writeUrl = "/schemas/filtered?path=/custom-employees&operation=post&schemaType=request";
        var write = request(writeUrl, HttpMethod.GET, "org-a", null, null);
        var writeSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(body(write));
        assertFalse(writeSchema.validate(json.readTree("{\"name\":\"Missing\"}")).isEmpty());
        assertEquals(400, request("/custom-employees", HttpMethod.POST, "org-a", "{\"name\":\"Missing\"}", null).getStatusCode().value());
        var upper = request(writeUrl.replace("schemaType=request", "schemaType=REQUEST"), HttpMethod.GET, "org-a", null, null);
        assertEquals(write.getHeaders().getETag(), upper.getHeaders().getETag());
        assertEquals(body(write), body(upper));
    }

    private String url(String path) { return "http://localhost:" + port + path; }
    private JsonNode body(ResponseEntity<String> response) throws Exception { return json.readTree(response.getBody()); }
    private ResponseEntity<String> request(String path, HttpMethod method, String tenant, String body, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) headers.set("X-Tenant", tenant);
        if (etag != null) headers.setIfNoneMatch(etag);
        return rest.exchange(URI.create(url(path)), method, new HttpEntity<>(body, headers), String.class);
    }

    @SpringBootApplication(exclude = SecurityAutoConfiguration.class)
    @EntityScan(basePackageClasses = CustomFieldsResourceE2ETest.class)
    @ComponentScan(basePackageClasses = CustomFieldsResourceE2ETest.class)
    @EnableJpaRepositories(basePackageClasses = CustomFieldsResourceE2ETest.class, considerNestedRepositories = true)
    static class FixtureApp {
        @Bean CustomFieldDefinitionProvider customFieldDefinitions() {
            return (resource, tenant, schemaType) -> {
                if (!RESOURCE.equals(resource)) return List.of();
                if ("org-a".equals(tenant)) return List.of(new CustomFieldDescriptor(
                        "numero_calcado", "Shoe size", "Uniform shoe size", CustomFieldDataType.INTEGER,
                        FieldControlType.AUTO, "Uniform", 1, true, false, true, false, true, null,
                        BigDecimal.valueOf(20), BigDecimal.valueOf(50), null, null, null, null, null,
                        List.of(), List.of(), null, Filterable.FilterOperation.EQUAL, Map.of()),
                        new CustomFieldDescriptor("auditFlag", "Audit", null, CustomFieldDataType.BOOLEAN,
                                null, null, 2, true, true, false, false, false, null,
                                null, null, null, null, null, null, null, null, null, null, null, null));
                if ("org-ops".equals(tenant)) return List.of(
                        new CustomFieldDescriptor("age", "Age", null, CustomFieldDataType.INTEGER,
                                null, null, 0, true, false, true, false, false, null,
                                BigDecimal.ONE, BigDecimal.valueOf(100), null, null, null, null, null,
                                null, null, null, Filterable.FilterOperation.IN, null),
                        new CustomFieldDescriptor("score", "Score", null, CustomFieldDataType.INTEGER,
                                null, null, 1, true, false, true, false, false, null,
                                null, null, null, null, null, null, null,
                                null, null, null, Filterable.FilterOperation.IS_NULL, null));
                if ("org-b".equals(tenant)) return List.of(new CustomFieldDescriptor(
                        "matricula", "Registration", null, CustomFieldDataType.STRING,
                        FieldControlType.AUTO, null, 1, false, false, true, false, true, null,
                        null, null, 2, 20, "[A-Z0-9]+", null, null,
                        List.of(), List.of(), null, Filterable.FilterOperation.EQUAL, Map.of()));
                return List.of();
            };
        }
    }

    @Entity @Table(name = "custom_fields_employee_test")
    public static class Employee {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        String name;
        @CustomFields(resourceKey = RESOURCE) @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
        Map<String, Object> customFields;
        public Employee() { }
        Employee(String name, Map<String, Object> fields) { this.name = name; this.customFields = fields; }
        public Long getId() { return id; }
        public String getName() { return name; }
        public Map<String, Object> getCustomFields() { return customFields; }
    }
    @Repository public interface EmployeeRepository extends BaseCrudRepository<Employee, Long> { }
    public record EmployeeResponse(Long id, String name, @CustomFields(resourceKey = RESOURCE) Map<String, Object> customFields) { }
    public record CreateEmployee(String name, @CustomFields(resourceKey = RESOURCE) Map<String, Object> customFields) { }
    public record UpdateEmployee(String name, @CustomFields(resourceKey = RESOURCE) Map<String, Object> customFields) { }
    public record EmployeeFilter(@CustomFields(resourceKey = RESOURCE) Map<String, Object> customFields) implements GenericFilterDTO { }

    @Service public static class EmployeeService extends AbstractBaseResourceService<Employee, EmployeeResponse, Long, EmployeeFilter, CreateEmployee, UpdateEmployee> {
        private final EmployeeRepository employees;
        private final CollectionExportExecutor exports;
        EmployeeService(EmployeeRepository repository, CollectionExportExecutor exports) {
            super(repository, Employee.class); this.employees = repository; this.exports = exports;
        }
        @Override protected String resolveResourceKey() { return RESOURCE; }
        @Override protected ResourceMapper<Employee, EmployeeResponse, CreateEmployee, UpdateEmployee, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                public EmployeeResponse toResponse(Employee e) { return new EmployeeResponse(e.id, e.name, e.customFields); }
                public Employee newEntity(CreateEmployee dto) { return new Employee(dto.name(), dto.customFields()); }
                public void applyUpdate(Employee e, UpdateEmployee dto) { e.name = dto.name(); e.customFields = dto.customFields(); }
                public Long extractId(Employee e) { return e.id; }
            };
        }
        @Override protected void beforeCreate(CreateEmployee dto, Employee entity) {
            if (entity.customFields != null && entity.customFields.containsKey("numero_calcado")) {
                entity.customFields = new java.util.LinkedHashMap<>(entity.customFields);
                entity.customFields.put("auditFlag", true);
            }
        }
        @Override public boolean supportsCollectionExport() { return true; }
        @Override public Optional<CollectionExportCapability> getCollectionExportCapability() {
            return Optional.of(new CollectionExportCapability(List.of(CollectionExportFormat.CSV, CollectionExportFormat.JSON), List.of(CollectionExportScope.ALL), Map.of(), false));
        }
        @Override public CollectionExportResult exportCollection(CollectionExportRequest<EmployeeFilter> request) {
            // The host admits the field; the canonical engine reads its nested record/Map path.
            return exports.export(request, employees.findAll().stream().map(getResourceMapper()::toResponse).toList(),
                    List.of(new CollectionExportField("customFields.numero_calcado", "Shoe size", true, true, "integer", "customFields.numero_calcado")),
                    CollectionExportValueResolver.reflective(), Map.of());
        }
    }
    @ApiResource(value = "/custom-employees", resourceKey = RESOURCE)
    public static class EmployeeController extends AbstractResourceController<EmployeeResponse, Long, EmployeeFilter, CreateEmployee, UpdateEmployee> {
        private final EmployeeService service;
        EmployeeController(EmployeeService service) { this.service = service; }
        @Override protected EmployeeService getService() { return service; }
        @Override protected Long getResponseId(EmployeeResponse response) { return response.id(); }
    }
}
