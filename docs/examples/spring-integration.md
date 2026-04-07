# Integracao Spring Boot ponta a ponta

Este exemplo mostra uma integracao minima entre uma aplicacao Spring Boot e o
`praxis-metadata-starter`.

## Objetivo

Demonstrar um caminho autocontido para:

- subir a aplicacao
- expor um controller de exemplo
- publicar `OpenAPI + x-ui`
- responder `/schemas/filtered`

## Regras

- use `@ApiResource` e `@ApiGroup`
- publique um DTO com `@UISchema`
- use o core resource-oriented canonico
- mantenha o contrato consumivel por `praxis-ui-angular`

## Exemplo minimo

```java
@ApiResource(value = ApiPaths.Catalog.CATEGORIAS, resourceKey = "catalog.categorias")
@ApiGroup("catalog")
public class CategoriaController
    extends AbstractReadOnlyResourceController<Categoria, CategoriaResponseDTO, Integer, CategoriaFilterDTO> {

    public CategoriaController(CategoriaService service) {
        super(service);
    }
}
```

```java
@Service
public class CategoriaService
    extends AbstractReadOnlyResourceService<Categoria, CategoriaResponseDTO, Integer, CategoriaFilterDTO> {

    public CategoriaService(CategoriaRepository repository, CategoriaMapper mapper) {
        super(repository, Categoria.class, mapper);
    }
}
```

## Exemplo de DTO de resposta

```java
public class CategoriaResponseDTO {
  @UISchema(label = "Nome")
  private String nome;
}
```

## Resultado esperado

- `GET /v3/api-docs` responde
- `GET /api/catalog/categorias/schemas` responde
- `/schemas/filtered` resolve request e response
