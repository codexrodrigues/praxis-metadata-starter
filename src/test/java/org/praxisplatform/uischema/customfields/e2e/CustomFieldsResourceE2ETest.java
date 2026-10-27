package org.praxisplatform.uischema.customfields.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.BeforeEach;
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
@EnabledIfEnvironmentVariable(named = "PRAXIS_CUSTOM_FIELDS_NEON_JDBC_URL", matches = ".+")
class CustomFieldsResourceE2ETest {
    static final String RESOURCE = "custom.employees";
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired EmployeeRepository repository;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void requireAssignedRemoteDatabase(DynamicPropertyRegistry properties) {
        String url = required("PRAXIS_CUSTOM_FIELDS_NEON_JDBC_URL");
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("A PostgreSQL JDBC URL is required");
        }
        URI uri = URI.create(url.substring("jdbc:".length()));
        Map<String, String> parameters = connectionParameters(uri);
        if (uri.getHost() == null || !uri.getHost().endsWith(".neon.tech") || uri.getHost().contains("-pooler")
                || !uri.getHost().equals(required("PRAXIS_CUSTOM_FIELDS_NEON_EXPECTED_HOST"))
                || uri.getUserInfo() != null || uri.getFragment() != null
                || !"verify-full".equals(parameters.get("sslmode"))
                || !"org.postgresql.ssl.DefaultJavaSSLFactory".equals(parameters.get("sslfactory"))) {
            throw new IllegalStateException("An explicitly assigned Neon endpoint with verified TLS is required");
        }
        if (!required("PRAXIS_CUSTOM_FIELDS_NEON_EXPECTED_BRANCH").startsWith("br-")) {
            throw new IllegalStateException("Explicit Neon branch assignment is required");
        }
        String schema = required("PRAXIS_CUSTOM_FIELDS_NEON_SCHEMA");
        if (!schema.matches("[a-z][a-z0-9_]{0,62}") || schema.equals("public")
                || !schema.equals(parameters.get("currentSchema"))) {
            throw new IllegalStateException("An isolated, precreated schema is required");
        }
        String database = uri.getPath() == null ? "" : uri.getPath().substring(1);
        if (!database.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalStateException("An explicitly named fixture database is required");
        }
        // Executes before application refresh / Hibernate create-drop, not after fixture DDL.
        verifyDatabaseBeforeDdl(url, database, schema);
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> required("PRAXIS_CUSTOM_FIELDS_NEON_USER"));
        properties.add("spring.datasource.password", () -> required("PRAXIS_CUSTOM_FIELDS_NEON_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        properties.add("spring.datasource.hikari.schema", () -> schema);
    }

    private static Map<String, String> connectionParameters(URI uri) {
        Set<String> allowed = Set.of("sslmode", "sslfactory", "currentSchema", "connectTimeout", "socketTimeout", "channelBinding");
        Map<String, String> parameters = new HashMap<>();
        if (uri.getRawQuery() == null) throw new IllegalStateException("Explicit verified TLS parameters are required");
        for (String pair : uri.getRawQuery().split("&", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) throw new IllegalStateException("Malformed JDBC parameter");
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (!allowed.contains(key) || parameters.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("Unsupported or duplicate JDBC parameter");
            }
        }
        if (parameters.containsKey("channelBinding") && !"require".equals(parameters.get("channelBinding"))) {
            throw new IllegalStateException("Explicit channel binding must be required");
        }
        for (String timeout : List.of("connectTimeout", "socketTimeout")) {
            if (parameters.containsKey(timeout) && !parameters.get(timeout).matches("[1-9][0-9]{0,2}")) {
                throw new IllegalStateException("Timeout must be a bounded positive number of seconds");
            }
        }
        return parameters;
    }

    private static void verifyDatabaseBeforeDdl(String url, String database, String schema) {
        String user = required("PRAXIS_CUSTOM_FIELDS_NEON_USER");
        Properties credentials = new Properties();
        credentials.setProperty("user", user);
        credentials.setProperty("password", required("PRAXIS_CUSTOM_FIELDS_NEON_PASSWORD"));
        try (var connection = DriverManager.getConnection(url, credentials)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                connection.setSchema(schema);
                try (var statement = connection.prepareStatement("""
                        select current_user, current_database(), current_schema(),
                               current_setting('server_version_num')::integer / 10000,
                               r.rolsuper or r.rolcreatedb or r.rolcreaterole or r.rolbypassrls,
                               exists (select 1 from pg_roles n where n.rolname='neon_superuser'
                                       and pg_has_role(current_user,n.oid,'MEMBER')),
                               exists (select 1 from pg_namespace n where n.nspname=?
                                       and has_schema_privilege(current_user,n.oid,'USAGE')
                                       and has_schema_privilege(current_user,n.oid,'CREATE'))
                        from pg_roles r where r.rolname=current_user
                        """)) {
                    statement.setString(1, schema);
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !user.equals(result.getString(1))
                                || !database.equals(result.getString(2)) || !schema.equals(result.getString(3))
                                || result.getInt(4) != 17 || result.getBoolean(5)
                                || result.getBoolean(6) || !result.getBoolean(7)) {
                            throw new IllegalStateException("Neon fixture identity, privileges, schema or PostgreSQL version mismatch before DDL");
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            // Do not propagate connection properties or credentials into test failure output.
            throw new IllegalStateException("Read-only Neon preflight failed before DDL (SQLState " + failure.getSQLState() + ")");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    @BeforeEach
    void verifyIdentityAndResetOnlyFixtureData() {
        assertEquals(required("PRAXIS_CUSTOM_FIELDS_NEON_USER"), jdbc.queryForObject("select current_user", String.class));
        assertEquals(required("PRAXIS_CUSTOM_FIELDS_NEON_SCHEMA"), jdbc.queryForObject("select current_schema()", String.class));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select rolsuper or rolcreatedb or rolcreaterole or rolbypassrls from pg_roles where rolname=current_user", Boolean.class)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pg_roles where rolname='neon_superuser' and pg_has_role(current_user,oid,'MEMBER'))", Boolean.class)));
        // Neon terminates client TLS at its proxy. pg_stat_ssl on the backend may be false;
        // the verified JDBC configuration and successful authenticated connection prove client TLS.
        assertEquals(17, jdbc.queryForObject("select current_setting('server_version_num')::integer / 10000", Integer.class));
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
                        List.of(), List.of(), null, Filterable.FilterOperation.EQUAL, Map.of()));
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
