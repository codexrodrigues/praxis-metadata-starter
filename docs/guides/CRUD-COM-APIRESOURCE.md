# CRUD com @ApiResource e @ApiGroup

Este guia mostra como expor recursos REST usando `@ApiResource` e organizar a documentação com `@ApiGroup`, aproveitando a auto‑detecção de base path no `AbstractCrudController`.

- Javadoc: [`@ApiResource`](../apidocs/org/praxisplatform/uischema/annotation/ApiResource.html), [`@ApiGroup`](../apidocs/org/praxisplatform/uischema/annotation/ApiGroup.html)
- Javadoc: [`AbstractCrudController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)
- Conceitos: [Documentação Técnica](../technical/), [Índice de API](../api/README.md)
 - Tour: [`Endpoints Overview`](../apidocs/org/praxisplatform/uischema/controller/base/doc-files/endpoints-overview.html)

## Passo a passo

1) Defina o path do recurso com `@ApiResource`

```java
@ApiResource("/api/human-resources/funcionarios")
@ApiGroup("human-resources")
public class FuncionarioController extends AbstractCrudController<Funcionario, FuncionarioDTO, Long, FuncionarioFilterDTO> {
    // ... apenas herança e wiring do service
}
```

2) Opcional: use constantes de path no projeto da aplicação

```java
public final class ApiPaths {
  public static final class HumanResources {
    public static final String FUNCIONARIOS = "/api/human-resources/funcionarios";
  }
}

@ApiResource(ApiPaths.HumanResources.FUNCIONARIOS)
@ApiGroup("human-resources")
public class FuncionarioController extends AbstractCrudController<...> { }
```

3) Auto‑detecção de base path no controller base

- O `AbstractCrudController` detecta o base path a partir de `@ApiResource`/`@RequestMapping` para:
  - montar links HATEOAS;  
  - compor URLs dos endpoints auxiliares (filter, options, etc.);
  - alinhar com a documentação gerada.

4) Organização no OpenAPI

- Use `@ApiGroup` para agrupar controllers por contexto (ex.: `human-resources`).
- A documentação OpenAPI é exposta por grupo; consulte o guia técnico para detalhes.

## Endpoints padrão do AbstractCrudController

- GET `/{id}` — busca registro por ID
- GET `/all` — lista completa (aplica ordenação padrão se configurada)
- POST `/filter` — paginação/filtragem
- POST `/filter/cursor` — paginação por cursor
- POST `/options/filter` — opções id/label (para selects)
- GET `/options/by-ids` — opções por IDs informados

> Veja Javadoc: [`AbstractCrudController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)

### Configurações úteis

- `praxis.pagination.max-size`: tamanho máximo por página (default: 200)
- `praxis.hateoas.enabled`: habilita/desabilita links HATEOAS nas respostas (default: true)

## Boas práticas

- Prefira constantes de path na aplicação (em vez de usar constantes do framework)
- Sempre defina `@ApiGroup` para facilitar a navegação no Swagger/OpenAPI
- Use `@DefaultSortColumn` na entidade para ordenação inicial previsível (veja o guia de Ordenação)

### Heurística de controlType (string)

- Campos `string` inferem `input` por padrão; `textarea` apenas quando `maxLength > 300`.
- Nomes como `nome`, `name`, `titulo`, `title`, `assunto`, `subject` forçam `input` (single-line).
- Nomes como `descricao`, `observacao`, `description`, `comment` forçam `textarea`.
- Precedência: `@UISchema(controlType=...)` > heurística por nome > detecção por schema > defaults.
- Detalhes: [Heurística de ControlType](../concepts/CONTROLTYPE-HEURISTICA.md)

## Referências

- [`@ApiResource`](../apidocs/org/praxisplatform/uischema/annotation/ApiResource.html)
- [`@ApiGroup`](../apidocs/org/praxisplatform/uischema/annotation/ApiGroup.html)
- [`AbstractCrudController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)
- [Ordenação Padrão](ORDEM-PADRAO.md)
