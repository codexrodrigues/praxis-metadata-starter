# AGENTS.md - Praxis Metadata Starter

This guide helps AI coding agents understand and contribute to the Praxis Metadata Starter codebase effectively.

## Architecture Overview

Praxis Metadata Starter is a Spring Boot library that enables metadata-driven backend development. It transforms annotated Java DTOs and controllers into enriched OpenAPI specifications with `x-ui` extensions, enabling automatic UI generation.

### Core Components
- **Auto-configurations**: `PraxisMetadataAutoConfiguration` and `OpenApiUiSchemaAutoConfiguration` wire components via component scanning.
- **Annotations**: `@UISchema` on DTOs defines UI properties; `@ApiResource` and `@ApiGroup` on controllers manage OpenAPI grouping.
- **Resolvers**: `CustomOpenApiResolver` converts annotations and Bean Validation into `x-ui` metadata.
- **Controllers**: `ApiDocsController` exposes `/schemas/filtered` (runtime contract) and `DomainCatalogController` exposes `/schemas/catalog` (documentation/RAG surface).
- **Canonical OpenAPI boundary**: `OpenApiDocumentService`, `CanonicalOperationResolver` and `SchemaReferenceResolver` own the canonical resolution of groups, operations and filtered schema links.
- **Services**: the canonical new core is `BaseResourceQueryService` + `BaseResourceCommandService` + `BaseResourceService`, implemented through `AbstractBaseQueryResourceService`, `AbstractBaseResourceService` and `AbstractReadOnlyResourceService`.
- **Legacy CRUD core**: `BaseCrudService`, `AbstractBaseCrudService` and `AbstractCrudController` still exist, but they are migration surface and should not receive new semantics.
- **Filters**: Dynamic JPA Specifications from `FilterDTO` classes annotated with `@Filterable`.

### Data Flow
1. DTOs with `@UISchema` + Bean Validation -> `CustomOpenApiResolver` -> enriched OpenAPI with `x-ui`.
2. Controllers with `@ApiResource` -> `DynamicSwaggerConfig` -> grouped OpenAPI documents.
3. Runtime: `/schemas/filtered` filters and caches schema payloads for UI consumption.
4. Groups reduce OpenAPI payload by ~97% vs. full docs.

### Key Packages
- `org.praxisplatform.uischema.annotation`: Core annotations.
- `org.praxisplatform.uischema.controller.base`: Legacy base controllers plus the migration target for the new `AbstractResource*Controller` hierarchy.
- `org.praxisplatform.uischema.service.base`: Canonical resource-oriented core implemented by `AbstractBaseQueryResourceService`, `AbstractBaseResourceService` and `AbstractReadOnlyResourceService`.
- `org.praxisplatform.uischema.filter`: Specification builders for dynamic queries.
- `org.praxisplatform.uischema.extension`: OpenAPI enrichment logic.

## Developer Workflows

### Building
- Use `./mvnw clean verify` for full build with tests.
- Java 21 required; Spring Boot 3.2+.
- Profiles: `release` for signed artifacts to Maven Central.

### Testing
- Unit tests in `src/test/java`.
- Integration tests validate OpenAPI generation and endpoints.
- Run `./mvnw test` or `./mvnw verify`.

### Releasing
- Tag versions as `vX.Y.Z` or `vX.Y.Z-rc.N`.
- Push tag triggers CI workflow for signing and publishing to Maven Central.
- Docs deploy automatically on main branch or version tags.

### Debugging
- Check `/v3/api-docs` for raw OpenAPI.
- Use `/schemas/filtered?group=your-group` for UI-ready schemas.
- Enable SpringDoc logging for resolver issues.

## Project-Specific Conventions

### Code Structure
- New canonical mutable services should extend `AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` and provide a `ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>`.
- Read-only services should extend `AbstractReadOnlyResourceService<E, ResponseDTO, ID, FilterDTO>`, which is query-only and does not inherit command methods.
- New canonical query controllers should extend `AbstractResourceQueryController<ResponseDTO, ID, FilterDTO>`.
- New canonical mutable controllers should extend `AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>`.
- New canonical read-only controllers should extend `AbstractReadOnlyResourceController<ResponseDTO, ID, FilterDTO>`, which does not publish write endpoints.
- Controllers based on `AbstractCrudController` are legacy and should only be touched when the migration explicitly requires it.
- Repositories extend `JpaRepository<E, ID>` and `JpaSpecificationExecutor<E>`.
- DTOs use Lombok (`@Data`, `@Builder`) and Bean Validation.
- Mappers use MapStruct with `CorporateMapperConfig`.

### Annotations Usage
- `@UISchema` on DTO fields: e.g., `@UISchema(control = FieldControlType.SELECT, endpoint = "/api/options/departments")`.
- `@Filterable` on FilterDTO fields for dynamic queries.
- `@ApiResource("/api/path")` and `@ApiGroup("group-name")` on controllers.

### Naming Patterns
- DTOs: `{Entity}DTO`
- Filters: `{Entity}FilterDTO`
- Mappers: `{Entity}Mapper`
- Services: `{Entity}Service`
- Controllers: `{Entity}Controller`
- Paths: `/api/{domain}/{resources}` (e.g., `/api/human-resources/employees`)

### Validation
- Use Jakarta Bean Validation on DTOs; auto-converted to `x-ui` constraints.
- Post-generation checklist: build passes, OpenAPI groups exist, CRUD endpoints respond, `/schemas/filtered` has `x-ui`, ETags work.

### External Integrations
- SpringDoc OpenAPI for base docs.
- MapStruct for entity-DTO mapping.
- JPA Specifications for filters (26 operations supported).
- HATEOAS links optional via `LinkBuilder`.

## Examples

### Legacy CRUD Controller
```java
@RestController
@ApiResource("/api/example/entities")
@ApiGroup("example")
public class EntityController extends AbstractCrudController<Entity, EntityDTO, Long, EntityFilterDTO> {
    // Implement getService(), toDto(), etc.
}
```

### Canonical Resource Controller
```java
@RestController
@ApiResource("/api/example/entities")
@ApiGroup("example")
public class EntityController extends AbstractResourceController<
        EntityResponseDTO,
        Long,
        EntityFilterDTO,
        CreateEntityDTO,
        UpdateEntityDTO> {

    @Override
    protected EntityService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(EntityResponseDTO dto) {
        return dto.getId();
    }
}
```

### Canonical Resource Service
```java
public class EmployeeService extends AbstractBaseResourceService<
        Employee,
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    @Override
    protected ResourceMapper<Employee, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> getResourceMapper() {
        return mapper;
    }
}
```

### DTO with UI Schema
```java
@UISchema
@Data
public class EntityDTO {
    @UISchema(control = FieldControlType.TEXT, label = "Name")
    @NotBlank
    private String name;
}
```

### Filter DTO
```java
@Filterable
@Data
public class EntityFilterDTO {
    @Filterable
    private String name;
}
```

Reference: `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md` and `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md` for full setup.
