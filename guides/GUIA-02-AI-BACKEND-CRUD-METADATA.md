# Guia 02 - IA Backend - CRUD Metadata-Driven por Entidade

## Objetivo

Este guia orienta uma LLM a gerar uma feature CRUD alinhada ao contrato
canonico do `praxis-metadata-starter`.

O objetivo nao e gerar "qualquer CRUD que compile". O objetivo e gerar:

- DTO com `@UISchema` e Bean Validation coerentes com `/schemas/filtered`
- `FilterDTO` com `@Filterable`
- `Mapper`, `Service`, `Repository` e `Controller` compativeis com
  `AbstractCrudController` e `AbstractBaseCrudService`
- endpoints e metadata consumiveis por `praxis-ui-angular` sem ajuste local

## Ordem de leitura para a LLM

Use este guia depois de `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`.

Ordem recomendada:

1. `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
2. este guia
3. `CHECKLIST-VALIDACAO-IA.md`

## Escopo correto deste guia

Este guia cobre o baseline canonico de um recurso CRUD metadata-driven.

Este guia nao deve tratar como obrigatorio:

- `BulkFilterAdapter`
- `BulkController`
- `org.praxisplatform.bulk.*`
- receitas herdadas de projetos externos

Se a feature precisar de bulk, isso e trilha opcional e separada.

## O que a LLM deve receber como entrada

No minimo:

1. entidade JPA ou sua estrutura
2. `resourcePath` canonico
3. grupo OpenAPI
4. pacote base do modulo

Exemplo:

```text
Gere uma feature CRUD metadata-driven alinhada ao praxis-metadata-starter.

Entrada:
- Entidade: src/main/java/com/example/hr/entity/Funcionario.java
- Resource path: /api/human-resources/funcionarios
- Api group: human-resources
- Pacote base: com.example.hr
```

## Arquivos minimos

```text
src/main/java/{pacote-base}/
|-- dto/
|   |-- {Nome}DTO.java
|   `-- filter/
|       `-- {Nome}FilterDTO.java
|-- mapper/
|   `-- {Nome}Mapper.java
|-- repository/
|   `-- {Nome}Repository.java
|-- service/
|   `-- {Nome}Service.java
`-- controller/
    `-- {Nome}Controller.java
```

## Padrao canonico do controller

O controller deve estender
`AbstractCrudController<E, D, ID, FD>` e implementar:

- `getService()`
- `toDto(...)`
- `toEntity(...)`
- `getEntityId(...)`
- `getDtoId(...)`

Exemplo estrutural:

```java
@RestController
@ApiResource(ApiPaths.HumanResources.FUNCIONARIOS)
@ApiGroup("human-resources")
public class FuncionarioController extends AbstractCrudController<Funcionario, FuncionarioDTO, Integer, FuncionarioFilterDTO> {

    private final FuncionarioService service;
    private final FuncionarioMapper mapper;

    public FuncionarioController(FuncionarioService service, FuncionarioMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    protected BaseCrudService<Funcionario, FuncionarioDTO, Integer, FuncionarioFilterDTO> getService() {
        return service;
    }

    @Override
    protected FuncionarioDTO toDto(Funcionario entity) {
        return mapper.toDto(entity);
    }

    @Override
    protected Funcionario toEntity(FuncionarioDTO dto) {
        return mapper.toEntity(dto);
    }

    @Override
    protected Integer getEntityId(Funcionario entity) {
        return entity.getId();
    }

    @Override
    protected Integer getDtoId(FuncionarioDTO dto) {
        return dto.getId();
    }
}
```

Regras:

- `@ApiResource` e a superficie canonica
- `ApiPaths` deve vir do projeto host
- o host so deve sobrescrever metodos quando precisar enriquecer OpenAPI

## Padrao canonico do service

O service padrao deve estender
`AbstractBaseCrudService<E, D, ID, FD>`.

```java
@Service
public class FuncionarioService extends AbstractBaseCrudService<Funcionario, FuncionarioDTO, Integer, FuncionarioFilterDTO> {

    public FuncionarioService(FuncionarioRepository repository) {
        super(repository, Funcionario.class);
    }

    @Override
    public Funcionario mergeUpdate(Funcionario existing, Funcionario payload) {
        existing.setNomeCompleto(payload.getNomeCompleto());
        existing.setCpf(payload.getCpf());
        existing.setEmail(payload.getEmail());
        existing.setAtivo(payload.getAtivo());
        return existing;
    }
}
```

Quando houver relacoes, `mergeUpdate(...)` deve preservar a semantica do
aggregate persistido.

## Padrao canonico do mapper

Padrao preferencial: MapStruct com `CorporateMapperConfig`.

```java
@Mapper(componentModel = "spring", config = CorporateMapperConfig.class)
public interface FuncionarioMapper {

    @Mapping(target = "cargoId", source = "cargo.id")
    FuncionarioDTO toDto(Funcionario entity);

    @Mapping(target = "cargo", expression = "java(cargoFromId(dto.getCargoId()))")
    Funcionario toEntity(FuncionarioDTO dto);

    default Cargo cargoFromId(Integer id) {
        if (id == null) return null;
        Cargo cargo = new Cargo();
        cargo.setId(id);
        return cargo;
    }
}
```

Regras:

- prefira `CorporateMapperConfig` quando houver MapStruct
- use mapper manual apenas se o projeto realmente adotar esse estilo
- ao mapear relacoes por ID, exponha `relacaoId` no DTO

## DTO canonico

O DTO deve refletir o contrato que a UI vai consumir via `/schemas/filtered`.

Regras obrigatorias:

- campos visiveis na UI devem usar `@UISchema`
- validacoes estruturais devem usar Bean Validation
- selects remotos devem usar `endpoint`, `valueField` e `displayField`
  coerentes com o endpoint real

## Apresentacao de valor canonica

Para campos escalares exibidos em modos table/list/read-only, o starter pode publicar `x-ui.valuePresentation` automaticamente.

Regra pratica:

- `valuePresentation` expressa a intencao semantica (`currency`, `number`, `percentage`, `date`, `datetime`, `time`, `boolean`)
- `format` nao substitui essa semantica; ele deve ser tratado como override explicito no consumidor
- para overrides raros, prefira `extraProperties` com chaves aninhadas, por exemplo `valuePresentation.type`

Publicacao automatica esperada:

- `numericFormat = CURRENCY` -> `valuePresentation.type = currency`
- `numericFormat = PERCENT` -> `valuePresentation.type = percentage`
- `format = date|date-time|time|currency|percent` -> tipo correspondente
- `controlType` escalar compativel -> tipo correspondente

Nao trate como `valuePresentation` automatico:

- selects e variantes inline de selecao
- ranges (`dateRange`, `dateTimeRange`, `priceRange`, etc.)
- arrays, objects e IDs tecnicos

Exemplo:

```java
@NotNull
@UISchema(
    label = "Cargo",
    controlType = FieldControlType.SELECT,
    valueField = "id",
    displayField = "label",
    endpoint = ApiPaths.HumanResources.CARGOS + "/options/filter",
    tableHidden = true
)
private Integer cargoId;
```

Exemplo de override via `extraProperties`:

```java
@UISchema(
    label = "Salario",
    numericFormat = NumericFormat.CURRENCY,
    extraProperties = {
        @ExtensionProperty(name = "valuePresentation.style", value = "short")
    }
)
private BigDecimal salario;
```

Regra critica:

- se o endpoint for `.../options/filter`, use `displayField = "label"`
- se o endpoint for `.../filter` retornando DTO completo, use o campo textual
  do DTO, como `nome`

## FilterDTO canonico

O `FilterDTO` deve implementar `GenericFilterDTO`.

Regras praticas:

- texto: `LIKE`
- boolean: `EQUAL`
- ranges numericos e datas: `BETWEEN`
- listas: `IN` ou `NOT_IN`
- relacoes: campo `...Id` com `relation = "relacao.id"`

Exemplo:

```java
@UISchema
@Filterable(operation = Filterable.FilterOperation.LIKE)
private String nomeCompleto;

@UISchema(type = FieldDataType.BOOLEAN, controlType = FieldControlType.CHECKBOX)
@Filterable(operation = Filterable.FilterOperation.EQUAL)
private Boolean ativo;

@UISchema(
    controlType = FieldControlType.SELECT,
    endpoint = ApiPaths.HumanResources.CARGOS + "/options/filter",
    valueField = "id",
    displayField = "label"
)
@Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "cargo.id")
private Integer cargoId;
```

Enums corretos:

- `FilterOperation.EQUAL`, nao `EQUALS`
- `FieldControlType.CHECKBOX` e um baseline seguro para booleanos simples
- `FieldControlType.TOGGLE_SWITCH` nao e canonico

## Endpoints que a UI espera

O contrato minimo do recurso deve ser compativel com:

- `GET /{resource}/schemas`
- `GET /schemas/filtered?path={resource}/all&operation=get&schemaType=response`
- `GET /schemas/filtered?path={resource}/filter&operation=post&schemaType=request`
- `POST /{resource}/filter`
- `POST /{resource}/filter/cursor`
- `POST /{resource}/locate`
- `POST /{resource}/options/filter`
- `GET /{resource}/options/by-ids`
- CRUD basico

## Como o Angular consome isso

`praxis-ui-angular` usa `GenericCrudService` para:

- chamar `getSchema()`
- resolver grid schema via `/schemas/filtered`
- resolver schema do filtro
- revalidar com `If-None-Match`
- ler `ETag`, `X-Schema-Hash` e `x-ui.resource.idField`

Implicacoes:

- o schema precisa expor `x-ui` coerente
- o DTO precisa manter `idField` inferivel ou sobrescrito corretamente
- endpoints de select precisam ser compativeis com `OptionDTO{id,label}`

## Resultado que este guia precisa produzir

Ao terminar este guia, o recurso precisa estar pronto para:

- CRUD baseline
- `/schemas/filtered` de request e response
- `POST /filter`
- `POST /options/filter` quando houver select remoto
- `GET /options/by-ids` quando houver select remoto
- consumo por `GenericCrudService` sem adaptacao local

## Prompt recomendado para IA

```text
Voce esta gerando uma feature CRUD metadata-driven para o ecossistema Praxis.

Siga o contrato canonico do praxis-metadata-starter.
Considere praxis-ui-angular como consumidor final do contrato.

Gere:
- DTO com @UISchema e Bean Validation
- FilterDTO com @Filterable
- Mapper com CorporateMapperConfig quando usar MapStruct
- Repository extendendo BaseCrudRepository
- Service extendendo AbstractBaseCrudService
- Controller extendendo AbstractCrudController

Nao trate bulk como obrigatorio.
So gere BulkController ou similares se o pedido mencionar explicitamente uma stack bulk externa.

Entrada:
- Entidade: {entity-path}
- Resource path: {resource-path}
- Api group: {api-group}
- Pacote base: {base-package}
```

## Checklist minimo

Antes de concluir:

- o controller usa `@ApiResource` e `@ApiGroup`
- o mapper usa `CorporateMapperConfig` quando aplicavel
- `FilterOperation` usa enums canonicos
- selects remotos apontam para `/options/filter` com `displayField = "label"`
- o recurso pode ser consumido por `GenericCrudService` sem adaptacao local

## Referencias publicas

- `praxis-metadata-starter/README.md`
- repositório público do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacote publicado de consumo de contrato: `@praxisui/core`
- `praxis-metadata-starter/docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
