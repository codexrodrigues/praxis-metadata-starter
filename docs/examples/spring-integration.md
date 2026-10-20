# Integração Spring Boot Ponta a Ponta

Este exemplo mostra uma integração mínima e compilável entre uma aplicação Spring Boot e o `praxis-metadata-starter`.

## Objetivo

Demonstrar um caminho autocontido para:

- subir a aplicação
- expor um controller read-only ou mutável no baseline canônico atual
- publicar `OpenAPI + x-ui`
- responder `/schemas/filtered`, `/schemas/domain`, `/{resource}/capabilities` e `/praxis/cockpit`

## Regras

- use `@ApiResource(value = ..., resourceKey = ...)` e `@ApiGroup` (não combine `@RestController` com `@ApiResource`)
- publique DTOs separados com `@Schema` e `@UISchema`
- use o core *resource-oriented* canônico (`AbstractResourceController` ou `AbstractReadOnlyResourceController`)
- mantenha o contrato consumível por `praxis-ui-angular`

## Exemplo mínimo (Read-Only)

```java
@ApiResource(value = ApiPaths.Catalog.CATEGORIAS, resourceKey = "catalog.categorias")
@ApiGroup("catalog")
public class CategoriaController
    extends AbstractReadOnlyResourceController<CategoriaResponseDTO, Integer, CategoriaFilterDTO> {

    private final CategoriaService service;

    public CategoriaController(CategoriaService service) {
        this.service = service;
    }

    @Override
    protected CategoriaService getService() {
        return service;
    }

    @Override
    protected Integer getResponseId(CategoriaResponseDTO dto) {
        return dto.getId();
    }
}
```

```java
@Service
public class CategoriaService
    extends AbstractReadOnlyResourceService<Categoria, CategoriaResponseDTO, Integer, CategoriaFilterDTO> {

    private final CategoriaMapper mapper;

    public CategoriaService(CategoriaRepository repository, CategoriaMapper mapper) {
        super(repository, Categoria.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<Categoria, CategoriaResponseDTO, ?, ?, Integer> getResourceMapper() {
        return mapper;
    }
}
```

## Exemplo de DTO de resposta

```java
public class CategoriaResponseDTO {

    @UISchema(label = "ID", hidden = true)
    private Integer id;

    @Schema(description = "Nome oficial da categoria exibido em catálogos e listagens.")
    @UISchema(label = "Nome")
    private String nome;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}
```

## Resultado esperado

- `GET /v3/api-docs/catalog` responde com o documento do grupo enriquecido com `x-ui`
- `GET /schemas/filtered?path=/api/catalog/categorias/all&operation=get&schemaType=response` resolve o contrato estrutural
- `GET /api/catalog/categorias/capabilities` publica o snapshot contextual com `readOnly=true`
- `GET /praxis/cockpit` exibe o recurso no cockpit automático do host
