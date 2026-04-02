package org.praxisplatform.uischema.controller.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIntent;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Iterator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AbstractResourceControllerJpaWriteIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.data.jpa.repositories.enabled=false",
                "spring.jpa.open-in-view=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:starter-resource-write-it;DB_CLOSE_DELAY=-1",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
@AutoConfigureMockMvc
class AbstractResourceControllerJpaWriteIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DepartmentRepository departmentRepository;

    @Autowired
    EmployeeRepository employeeRepository;

    @LocalServerPort
    int port;

    private Long hrId;
    private Long opsId;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        departmentRepository.deleteAll();

        Department hr = new Department();
        hr.setNome("Human Resources");
        hrId = departmentRepository.save(hr).getId();

        Department ops = new Department();
        ops.setNome("Operations");
        opsId = departmentRepository.save(ops).getId();
    }

    @Test
    void createReturnsCanonicalResponseDtoWithLocationAndDatasetVersion() throws Exception {
        MvcResult result = mockMvc.perform(post("/integration-employees")
                        .contentType("application/json")
                        .content("""
                                {
                                  "nome": "Alice",
                                  "departmentId": %d
                                }
                                """.formatted(hrId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Data-Version", "it"))
                .andExpect(jsonPath("$.data.nome").value("Alice"))
                .andExpect(jsonPath("$.data.departmentId").value(hrId))
                .andExpect(jsonPath("$.data.departmentNome").value("Human Resources"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode responseNode = objectMapper.readTree(body);
        long employeeId = responseNode.path("data").path("id").asLong(-1);
        assertTrue(employeeId > 0, "Response body does not contain employee id: " + body);
        assertEquals("http://localhost/integration-employees/" + employeeId, result.getResponse().getHeader("Location"));
    }

    @Test
    void updateReturnsCanonicalResponseDtoWithLazyAssociationResolved() throws Exception {
        Long employeeId = createEmployee("Alice", hrId).getId();

        mockMvc.perform(put("/integration-employees/{id}", employeeId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "nome": "Alice Updated",
                                  "departmentId": %d
                                }
                                """.formatted(opsId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "it"))
                .andExpect(jsonPath("$.data.id").value(employeeId))
                .andExpect(jsonPath("$.data.nome").value("Alice Updated"))
                .andExpect(jsonPath("$.data.departmentId").value(opsId))
                .andExpect(jsonPath("$.data.departmentNome").value("Operations"));
    }

    @Test
    void patchProfileIntentUpdatesOnlyProfileFieldsAndPreservesDepartment() throws Exception {
        Long employeeId = createEmployee("Alice", hrId).getId();

        mockMvc.perform(patch("/integration-employees/{id}/profile", employeeId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "nome": "Alice Profile"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "it"))
                .andExpect(jsonPath("$.data.id").value(employeeId))
                .andExpect(jsonPath("$.data.nome").value("Alice Profile"))
                .andExpect(jsonPath("$.data.departmentId").value(hrId))
                .andExpect(jsonPath("$.data.departmentNome").value("Human Resources"));

        Employee updated = employeeRepository.findById(employeeId).orElseThrow();
        assertEquals("Alice Profile", updated.getNome());
        assertEquals(hrId, updated.getDepartment().getId());
    }

    @Test
    void readSurfaceRemainsCanonicalForGetByIdAndByIds() throws Exception {
        Long aliceId = createEmployee("Alice", hrId).getId();
        Long bobId = createEmployee("Bob", opsId).getId();

        mockMvc.perform(get("/integration-employees/{id}", aliceId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "it"))
                .andExpect(jsonPath("$.data.id").value(aliceId))
                .andExpect(jsonPath("$.data.nome").value("Alice"))
                .andExpect(jsonPath("$.data.departmentNome").value("Human Resources"));

        mockMvc.perform(get("/integration-employees/by-ids")
                        .param("ids", String.valueOf(bobId), String.valueOf(aliceId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "it"))
                .andExpect(jsonPath("$[0].id").value(bobId))
                .andExpect(jsonPath("$[0].nome").value("Bob"))
                .andExpect(jsonPath("$[1].id").value(aliceId))
                .andExpect(jsonPath("$[1].nome").value("Alice"));
    }

    @Test
    void collectionSurfaceRemainsCanonicalForGetAll() throws Exception {
        createEmployee("Alice", hrId);
        createEmployee("Bob", opsId);

        mockMvc.perform(get("/integration-employees/all"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "it"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].nome").value("Alice"))
                .andExpect(jsonPath("$.data[1].nome").value("Bob"));
    }

    @Test
    void deleteAndBatchDeleteRemoveEmployeesThroughCanonicalEndpoints() throws Exception {
        Long aliceId = createEmployee("Alice", hrId).getId();
        Long bobId = createEmployee("Bob", opsId).getId();

        mockMvc.perform(delete("/integration-employees/{id}", aliceId))
                .andExpect(status().isNoContent());
        assertFalse(employeeRepository.findById(aliceId).isPresent());

        mockMvc.perform(delete("/integration-employees/batch")
                        .contentType("application/json")
                        .content("[" + bobId + "]"))
                .andExpect(status().isNoContent());
        assertFalse(employeeRepository.findById(bobId).isPresent());
        assertEquals(0L, employeeRepository.count());
    }

    @Test
    void schemasEndpointRedirectsToCanonicalFilteredResponseSchema() throws Exception {
        mockMvc.perform(get("/integration-employees/schemas"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/schemas/filtered?path=/integration-employees/all&operation=get&schemaType=response&idField=id&readOnly=false"
                ));
    }

    @Test
    void openApiDocumentSeparatesCreateUpdateAndIntentPatchRequestDtos() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs/integration-employees"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String createRef = resolveSchemaRef(root.path("paths").path("/integration-employees").path("post").path("requestBody").path("content"));
        String updateRef = resolveSchemaRef(root.path("paths").path("/integration-employees/{id}").path("put").path("requestBody").path("content"));
        String profilePatchRef = resolveSchemaRef(root.path("paths").path("/integration-employees/{id}/profile").path("patch").path("requestBody").path("content"));

        assertTrue(createRef.endsWith("/CreateEmployeeDto"), "Expected create DTO ref, got: " + createRef);
        assertTrue(updateRef.endsWith("/UpdateEmployeeDto"), "Expected update DTO ref, got: " + updateRef);
        assertTrue(profilePatchRef.endsWith("/UpdateEmployeeProfileDto"), "Expected profile patch DTO ref, got: " + profilePatchRef);
    }

    @Test
    void openApiDocumentUsesFlattenedRestApiResourceShapeForCollectionResponses() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs/integration-employees"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode allRowSchema = resolveCollectionItemSchema(root, "/integration-employees/all", "get");
        assertTrue(allRowSchema.path("properties").has("nome"));
        assertTrue(allRowSchema.path("properties").has("departmentId"));
        assertTrue(allRowSchema.path("properties").has("_links"));
        assertFalse(allRowSchema.path("properties").has("content"));
        assertFalse(allRowSchema.path("properties").has("links"));
        assertCanonicalLinksSchema(root, allRowSchema);

        JsonNode filterRowSchema = resolveCollectionItemSchema(root, "/integration-employees/filter", "post");
        assertTrue(filterRowSchema.path("properties").has("nome"));
        assertTrue(filterRowSchema.path("properties").has("_links"));
        assertFalse(filterRowSchema.path("properties").has("content"));
        assertCanonicalLinksSchema(root, filterRowSchema);

        JsonNode cursorRowSchema = resolveCollectionItemSchema(root, "/integration-employees/filter/cursor", "post");
        assertTrue(cursorRowSchema.path("properties").has("nome"));
        assertTrue(cursorRowSchema.path("properties").has("_links"));
        assertFalse(cursorRowSchema.path("properties").has("content"));
        assertCanonicalLinksSchema(root, cursorRowSchema);
    }

    @Test
    void openApiDocumentUsesCanonicalLinksShapeForItemResponses() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs/integration-employees"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode itemResponseSchema = resolveSchemaNode(
                root,
                root.path("paths")
                        .path("/integration-employees/{id}")
                        .path("get")
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path("application/json")
                        .path("schema")
        );
        JsonNode itemDataSchema = resolveSchemaNode(root, itemResponseSchema.path("properties").path("data"));

        assertTrue(itemResponseSchema.path("properties").has("_links"));
        assertTrue(itemDataSchema.path("properties").has("nome"));
        assertTrue(itemDataSchema.path("properties").has("departmentId"));
        assertFalse(itemDataSchema.path("properties").has("content"));
        assertCanonicalLinksSchema(root, itemResponseSchema);
    }

    @Test
    void filteredSchemasResolveCanonicalCreateUpdateAndIntentPatchContracts() throws Exception {
        JsonNode createSchema = getSchema("/integration-employees", "post", "request");
        JsonNode updateSchema = getSchema("/integration-employees/{id}", "put", "request");
        JsonNode profilePatchSchema = getSchema("/integration-employees/{id}/profile", "patch", "request");

        assertEquals("string", createSchema.path("properties").path("nome").path("type").asText());
        assertEquals("integer", createSchema.path("properties").path("departmentId").path("type").asText());
        assertEquals("id", createSchema.path("x-ui").path("resource").path("idField").asText());
        assertFalse(createSchema.path("x-ui").path("resource").path("readOnly").asBoolean());

        assertEquals("string", updateSchema.path("properties").path("nome").path("type").asText());
        assertEquals("integer", updateSchema.path("properties").path("departmentId").path("type").asText());
        assertEquals("id", updateSchema.path("x-ui").path("resource").path("idField").asText());
        assertFalse(updateSchema.path("x-ui").path("resource").path("readOnly").asBoolean());

        assertEquals("string", profilePatchSchema.path("properties").path("nome").path("type").asText());
        assertFalse(profilePatchSchema.path("properties").has("departmentId"));
        assertEquals("id", profilePatchSchema.path("x-ui").path("resource").path("idField").asText());
        assertFalse(profilePatchSchema.path("x-ui").path("resource").path("readOnly").asBoolean());
    }

    @Test
    void profilePatchEndpointCarriesResourceIntentMetadata() throws Exception {
        ResourceIntent intent = EmployeeController.class
                .getDeclaredMethod("updateProfile", Long.class, UpdateEmployeeProfileDto.class)
                .getAnnotation(ResourceIntent.class);

        assertNotNull(intent);
        assertEquals("employee-profile", intent.id());
        assertEquals("Editar perfil", intent.title());
    }

    @Test
    void surfacesCatalogPublishesAutomaticAndExplicitSurfacesForPilotResource() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                URI.create("http://localhost:" + port + "/schemas/surfaces?resource=integration.employees"),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode catalog = objectMapper.readTree(response.getBody());
        assertEquals("integration.employees", catalog.path("resourceKey").asText());
        assertEquals("/integration-employees", catalog.path("resourcePath").asText());
        assertTrue(catalog.path("surfaces").isArray());

        JsonNode createSurface = findSurface(catalog.path("surfaces"), "create");
        assertNotNull(createSurface);
        assertEquals("FORM", createSurface.path("kind").asText());
        assertEquals("COLLECTION", createSurface.path("scope").asText());
        assertEquals("POST", createSurface.path("method").asText());
        assertEquals("/integration-employees", createSurface.path("path").asText());
        assertTrue(createSurface.path("schemaUrl").asText().contains("schemaType=request"));
        assertFalse(createSurface.has("fields"));
        assertFalse(createSurface.has("schema"));

        JsonNode profileSurface = findSurface(catalog.path("surfaces"), "profile");
        assertNotNull(profileSurface);
        assertEquals("PARTIAL_FORM", profileSurface.path("kind").asText());
        assertEquals("ITEM", profileSurface.path("scope").asText());
        assertEquals("PATCH", profileSurface.path("method").asText());
        assertEquals("/integration-employees/{id}/profile", profileSurface.path("path").asText());
        assertEquals("profile", profileSurface.path("intent").asText());
        assertEquals("updateProfile", profileSurface.path("operationId").asText());
        assertTrue(profileSurface.path("schemaUrl").asText().contains("schemaType=request"));
        assertFalse(profileSurface.path("availability").path("allowed").asBoolean());
        assertEquals("resource-context-required", profileSurface.path("availability").path("reason").asText());
        assertFalse(profileSurface.path("availability").path("metadata").path("contextual").asBoolean());
    }

    @Test
    void itemSurfaceCatalogPublishesOnlyItemScopedSurfacesWithConcreteResourceId() throws Exception {
        Long employeeId = createEmployee("Alice", hrId).getId();

        ResponseEntity<String> response = restTemplate.getForEntity(
                URI.create("http://localhost:" + port + "/integration-employees/" + employeeId + "/surfaces"),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode catalog = objectMapper.readTree(response.getBody());
        assertEquals("integration.employees", catalog.path("resourceKey").asText());
        assertEquals("/integration-employees", catalog.path("resourcePath").asText());
        assertEquals("integration-employees", catalog.path("group").asText());
        assertEquals(employeeId.longValue(), catalog.path("resourceId").asLong());

        JsonNode surfaces = catalog.path("surfaces");
        assertNotNull(findSurface(surfaces, "detail"));
        assertNotNull(findSurface(surfaces, "edit"));
        assertNotNull(findSurface(surfaces, "profile"));
        assertEquals(null, findSurface(surfaces, "create"));
        assertEquals(null, findSurface(surfaces, "list"));

        for (JsonNode surface : surfaces) {
            assertEquals("ITEM", surface.path("scope").asText());
            assertTrue(surface.path("availability").path("allowed").asBoolean());
            assertTrue(surface.path("availability").path("metadata").path("contextual").asBoolean());
        }
    }

    @Test
    void itemSurfaceCatalogCapturesTenantPresenceInAvailabilityMetadata() throws Exception {
        Long employeeId = createEmployee("Alice", hrId).getId();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Tenant", "tenant-a");

        ResponseEntity<String> response = restTemplate.exchange(
                URI.create("http://localhost:" + port + "/integration-employees/" + employeeId + "/surfaces"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode catalog = objectMapper.readTree(response.getBody());
        JsonNode detail = findSurface(catalog.path("surfaces"), "detail");
        assertNotNull(detail);
        assertTrue(detail.path("availability").path("metadata").path("tenantPresent").asBoolean());
        assertTrue(detail.path("availability").path("metadata").path("contextual").asBoolean());
    }

    @Test
    void collectionCapabilitiesAggregateCanonicalOperationsAndCollectionSurfaces() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                URI.create("http://localhost:" + port + "/integration-employees/capabilities"),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals("it", response.getHeaders().getFirst("X-Data-Version"));

        JsonNode snapshot = objectMapper.readTree(response.getBody());
        assertEquals("integration.employees", snapshot.path("resourceKey").asText());
        assertEquals("/integration-employees", snapshot.path("resourcePath").asText());
        assertEquals("integration-employees", snapshot.path("group").asText());
        assertTrue(snapshot.path("resourceId").isNull());
        assertEquals(Boolean.TRUE, snapshot.path("canonicalOperations").path("create").asBoolean());
        assertEquals(Boolean.TRUE, snapshot.path("canonicalOperations").path("update").asBoolean());
        assertEquals(Boolean.TRUE, snapshot.path("canonicalOperations").path("delete").asBoolean());
        assertNotNull(findSurface(snapshot.path("surfaces"), "create"));
        assertNotNull(findSurface(snapshot.path("surfaces"), "list"));
        assertEquals(null, findSurface(snapshot.path("surfaces"), "detail"));
        assertEquals(0, snapshot.path("actions").size());
    }

    @Test
    void itemCapabilitiesAggregateItemSurfacesAndKeepActionsEmptyWhenNotPublished() throws Exception {
        Long employeeId = createEmployee("Alice", hrId).getId();

        ResponseEntity<String> response = restTemplate.getForEntity(
                URI.create("http://localhost:" + port + "/integration-employees/" + employeeId + "/capabilities"),
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode snapshot = objectMapper.readTree(response.getBody());
        assertEquals(employeeId.longValue(), snapshot.path("resourceId").asLong());
        assertNotNull(findSurface(snapshot.path("surfaces"), "detail"));
        assertNotNull(findSurface(snapshot.path("surfaces"), "edit"));
        assertNotNull(findSurface(snapshot.path("surfaces"), "profile"));
        assertEquals(0, snapshot.path("actions").size());
    }

    private Employee createEmployee(String nome, Long departmentId) {
        Employee employee = new Employee();
        employee.setNome(nome);
        employee.setDepartment(departmentRepository.findById(departmentId).orElseThrow());
        return employeeRepository.save(employee);
    }

    private String resolveSchemaRef(JsonNode contentNode) {
        assertNotNull(contentNode);
        JsonNode schemaNode = contentNode.path("application/json").path("schema");
        if (schemaNode.isMissingNode() || schemaNode.path("$ref").isMissingNode()) {
            Iterator<String> mediaTypes = contentNode.fieldNames();
            while (mediaTypes.hasNext()) {
                JsonNode candidate = contentNode.path(mediaTypes.next()).path("schema");
                if (!candidate.isMissingNode() && !candidate.path("$ref").isMissingNode()) {
                    schemaNode = candidate;
                    break;
                }
            }
        }
        String ref = schemaNode.path("$ref").asText();
        assertTrue(ref.startsWith("#/components/schemas/"), "Schema ref not found: " + schemaNode);
        return ref;
    }

    private JsonNode resolveCollectionItemSchema(JsonNode root, String path, String operation) {
        JsonNode responseSchema = resolveSchemaNode(
                root,
                root.path("paths")
                        .path(path)
                        .path(operation)
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path("application/json")
                        .path("schema")
        );
        JsonNode dataSchema = resolveSchemaNode(root, responseSchema.path("properties").path("data"));
        JsonNode contentSchema = "array".equals(dataSchema.path("type").asText())
                ? dataSchema
                : resolveSchemaNode(root, dataSchema.path("properties").path("content"));
        assertEquals("array", contentSchema.path("type").asText(), "Expected collection content array for " + path);
        return resolveSchemaNode(root, contentSchema.path("items"));
    }

    private void assertCanonicalLinksSchema(JsonNode root, JsonNode rowSchema) {
        JsonNode linksSchema = resolveSchemaNode(root, rowSchema.path("properties").path("_links"));
        assertEquals("object", linksSchema.path("type").asText());
        assertFalse(linksSchema.path("properties").has("empty"));
        JsonNode additionalProperties = linksSchema.path("additionalProperties");
        assertTrue(additionalProperties.has("oneOf"));
        assertEquals(2, additionalProperties.path("oneOf").size());

        JsonNode singleLink = resolveSchemaNode(root, additionalProperties.path("oneOf").get(0));
        assertEquals("object", singleLink.path("type").asText());
        assertTrue(singleLink.path("properties").has("href"));

        JsonNode repeatedLinks = additionalProperties.path("oneOf").get(1);
        assertEquals("array", repeatedLinks.path("type").asText());
        JsonNode repeatedLinkItem = resolveSchemaNode(root, repeatedLinks.path("items"));
        assertTrue(repeatedLinkItem.path("properties").has("href"));
    }

    private JsonNode resolveSchemaNode(JsonNode root, JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isMissingNode() || schemaNode.isNull()) {
            return schemaNode;
        }
        if (schemaNode.has("$ref")) {
            return resolveSchemaNode(root, resolveRef(root, schemaNode.path("$ref").asText()));
        }
        if (schemaNode.has("allOf") && schemaNode.path("allOf").isArray() && !schemaNode.path("allOf").isEmpty()) {
            com.fasterxml.jackson.databind.node.ObjectNode merged = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode mergedProperties = objectMapper.createObjectNode();
            for (JsonNode candidate : schemaNode.path("allOf")) {
                JsonNode resolved = resolveSchemaNode(root, candidate);
                if (resolved != null && resolved.has("properties")) {
                    resolved.path("properties").fields()
                            .forEachRemaining(entry -> mergedProperties.set(entry.getKey(), entry.getValue()));
                }
            }
            if (!mergedProperties.isEmpty()) {
                merged.set("properties", mergedProperties);
                return merged;
            }
        }
        return schemaNode;
    }

    private JsonNode resolveRef(JsonNode root, String ref) {
        assertTrue(ref.startsWith("#/"), "Unsupported ref: " + ref);
        JsonNode current = root;
        for (String token : ref.substring(2).split("/")) {
            current = current.path(token);
        }
        assertFalse(current.isMissingNode(), "Missing component for ref " + ref);
        return current;
    }

    private JsonNode getSchema(String path, String operation, String schemaType) throws Exception {
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + port + "/schemas/filtered")
                .queryParam("path", path)
                .queryParam("operation", operation)
                .queryParam("schemaType", schemaType)
                .build()
                .encode()
                .toUri();

        ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
        assertEquals(200, response.getStatusCode().value());
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode findSurface(JsonNode surfaces, String id) {
        for (JsonNode surface : surfaces) {
            if (id.equals(surface.path("id").asText())) {
                return surface;
            }
        }
        return null;
    }

    @EnableAutoConfiguration
    @EnableJpaRepositories(
            considerNestedRepositories = true,
            basePackageClasses = AbstractResourceControllerJpaWriteIntegrationTest.class
    )
    @Import({EmployeeController.class, EmployeeService.class, EmployeeResourceMapper.class})
    static class TestConfig {
    }
}

@Entity
@Table(name = "it_departments")
class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nome;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}

@Entity
@Table(name = "it_employees")
class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }
}

class EmployeeResponseDto {

    private Long id;
    private String nome;
    private Long departmentId;
    private String departmentNome;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentNome() {
        return departmentNome;
    }

    public void setDepartmentNome(String departmentNome) {
        this.departmentNome = departmentNome;
    }
}

class CreateEmployeeDto {

    @NotBlank
    private String nome;

    @NotNull
    private Long departmentId;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }
}

class UpdateEmployeeDto {

    @NotBlank
    private String nome;

    @NotNull
    private Long departmentId;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }
}

class UpdateEmployeeProfileDto {

    @NotBlank
    private String nome;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}

class EmployeeFilterDto implements GenericFilterDTO {
}

@org.springframework.stereotype.Component
class EmployeeResourceMapper implements ResourceMapper<
        Employee,
        EmployeeResponseDto,
        CreateEmployeeDto,
        UpdateEmployeeDto,
        Long> {

    @Override
    public EmployeeResponseDto toResponse(Employee entity) {
        EmployeeResponseDto dto = new EmployeeResponseDto();
        dto.setId(entity.getId());
        dto.setNome(entity.getNome());
        dto.setDepartmentId(entity.getDepartment().getId());
        dto.setDepartmentNome(entity.getDepartment().getNome());
        return dto;
    }

    @Override
    public Employee newEntity(CreateEmployeeDto dto) {
        Employee entity = new Employee();
        entity.setNome(dto.getNome());
        entity.setDepartment(departmentFromId(dto.getDepartmentId()));
        return entity;
    }

    @Override
    public void applyUpdate(Employee entity, UpdateEmployeeDto dto) {
        entity.setNome(dto.getNome());
        entity.setDepartment(departmentFromId(dto.getDepartmentId()));
    }

    @Override
    public Long extractId(Employee entity) {
        return entity.getId();
    }

    private Department departmentFromId(Long id) {
        if (id == null) {
            return null;
        }
        Department department = new Department();
        department.setId(id);
        return department;
    }
}

interface DepartmentRepository extends BaseCrudRepository<Department, Long> {
}

interface EmployeeRepository extends BaseCrudRepository<Employee, Long> {
}

@org.springframework.stereotype.Service
class EmployeeService extends AbstractBaseResourceService<
        Employee,
        EmployeeResponseDto,
        Long,
        EmployeeFilterDto,
        CreateEmployeeDto,
        UpdateEmployeeDto> {

    private final EmployeeResourceMapper mapper;

    EmployeeService(EmployeeRepository repository, EmployeeResourceMapper mapper) {
        super(repository, Employee.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<Employee, EmployeeResponseDto, CreateEmployeeDto, UpdateEmployeeDto, Long> getResourceMapper() {
        return mapper;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("it");
    }

    @Transactional
    public EmployeeResponseDto updateProfile(Long id, UpdateEmployeeProfileDto dto) {
        Employee existing = findEntityById(id);
        existing.setNome(dto.getNome());
        Employee saved = getRepository().save(existing);
        if (getEntityManager() != null) {
            getEntityManager().flush();
            Employee managed = getEntityManager().contains(saved) ? saved : getEntityManager().merge(saved);
            getEntityManager().refresh(managed);
            return getResourceMapper().toResponse(managed);
        }
        return getResourceMapper().toResponse(saved);
    }
}

@ApiResource(value = "/integration-employees", resourceKey = "integration.employees")
class EmployeeController extends AbstractResourceController<
        EmployeeResponseDto,
        Long,
        EmployeeFilterDto,
        CreateEmployeeDto,
        UpdateEmployeeDto> {

    private final EmployeeService service;

    EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @Override
    protected EmployeeService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(EmployeeResponseDto dto) {
        return dto.getId();
    }

    @PatchMapping("/{id}/profile")
    @Operation(summary = "Atualizar perfil do funcionario")
    @UiSurface(
            id = "profile",
            kind = SurfaceKind.PARTIAL_FORM,
            scope = SurfaceScope.ITEM,
            title = "Editar perfil",
            description = "Atualiza apenas os dados de perfil do funcionario",
            intent = "profile",
            order = 50,
            tags = {"profile"}
    )
    @ResourceIntent(
            id = "employee-profile",
            title = "Editar perfil",
            description = "Atualiza apenas os dados de perfil do funcionario"
    )
    public ResponseEntity<RestApiResponse<EmployeeResponseDto>> updateProfile(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody UpdateEmployeeProfileDto dto
    ) {
        EmployeeResponseDto updated = service.updateProfile(id, dto);
        org.springframework.hateoas.Links links = org.springframework.hateoas.Links.of(
                linkToSelf(id),
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUpdate(id),
                linkToDelete(id),
                linkToUiSchema("/{id}/profile", "patch", "request")
        );
        return withVersion(ResponseEntity.ok(), RestApiResponse.success(updated, hateoasOrNull(links)));
    }
}
