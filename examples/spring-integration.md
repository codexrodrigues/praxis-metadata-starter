# Integracao Spring Boot ponta a ponta

Este exemplo mostra uma integracao minima entre uma aplicacao Spring Boot e o
`praxis-metadata-starter`.

## Objetivo

Demonstrar um caminho autocontido para:

- subir a aplicacao
- expor um controller CRUD
- publicar `OpenAPI + x-ui`
- responder `/schemas/filtered`

## Regras

- use `@ApiResource` e `@ApiGroup`
- publique um DTO com `@UISchema`
- use `AbstractCrudController`
- mantenha o contrato consumivel por `praxis-ui-angular`

## Exemplo de controller

```java
@RestController
@ApiResource(ApiPaths.Catalog.CATEGORIAS)
@ApiGroup("catalog")
public class CategoriaController extends AbstractCrudController<Categoria, CategoriaDTO, Integer, CategoriaFilterDTO> {
}
```

## Exemplo de DTO

```java
public class CategoriaDTO {
  @UISchema(label = "Nome")
  private String nome;
}
```

## Resultado esperado

- `GET /v3/api-docs` responde
- `GET /api/catalog/categorias/schemas` responde
- `/schemas/filtered` resolve request e response
