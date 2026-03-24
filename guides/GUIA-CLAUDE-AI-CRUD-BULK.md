# Guia para Agentes de IA - Gerar CRUD Metadata-Driven por Entidade

> Nota de escopo: apesar do nome do arquivo mencionar `CRUD-BULK`, este guia documenta o baseline canônico de CRUD metadata-driven. Bulk continua opcional e externo a este fluxo minimo.

## Objetivo

Este guia orienta um agente de IA a gerar uma feature CRUD alinhada ao contrato canônico do `praxis-metadata-starter`.

O objetivo nao e gerar "qualquer CRUD que compile". O objetivo e gerar:

- DTOs com `@UISchema` e Bean Validation coerentes com o contrato publicado em `/schemas/filtered`
- `FilterDTO` com `@Filterable` coerente com os endpoints base do starter
- `Mapper`, `Service`, `Repository` e `Controller` compatíveis com `AbstractCrudController` e `AbstractBaseCrudService`
- endpoints e metadata que possam ser consumidos sem ajuste local por `praxis-api-quickstart` e `praxis-ui-angular`

## Fontes canonicas

Antes de gerar codigo, o agente deve se orientar por esta hierarquia:

1. `praxis-metadata-starter`
   - fonte canonica de `@ApiResource`, `@ApiGroup`, `@UISchema`, `Filterable`, `AbstractCrudController`, `AbstractBaseCrudService`, `/schemas/filtered` e endpoints de options
2. `praxis-api-quickstart`
   - host operacional de referencia para uso real e publicado
3. `praxis-ui-angular`
   - consumidor final de runtime, especialmente `GenericCrudService`

## Escopo correto deste guia

Este guia cobre o padrao canônico de um recurso CRUD metadata-driven.

Este guia nao deve tratar como obrigatorio:

- `BulkFilterAdapter`
- `BulkController`
- `org.praxisplatform.bulk.*`
- receitas herdadas de projetos externos como `ms-pessoa-ananke`

Se a feature precisar de bulk, isso deve ser tratado como trilha opcional e externa ao contrato minimo deste starter.

## O que o agente deve receber como entrada

O agente deve receber, no minimo:

1. caminho ou codigo da entidade JPA
2. path canônico do recurso
3. grupo OpenAPI desejado
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

## Arquivos minimos a gerar

Para um recurso CRUD padrão, o conjunto minimo canônico e:

```text
src/main/java/{pacote-base}/
├── dto/
│   ├── {Nome}DTO.java
│   └── filter/
│       └── {Nome}FilterDTO.java
├── mapper/
│   └── {Nome}Mapper.java
├── repository/
│   └── {Nome}Repository.java
├── service/
│   └── {Nome}Service.java
└── controller/
    └── {Nome}Controller.java
```

`BulkController` e `BulkFilterAdapter` nao fazem parte do baseline do starter.

## Padrao canônico do Controller

O controller deve estender `AbstractCrudController<E, D, ID, FD>` e implementar:

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

Observacoes:

- `@ApiResource` e a superficie canônica
- `ApiPaths` deve vir do projeto host, nao do starter
- o host pode sobrescrever metodos do controller apenas para enriquecer OpenAPI ou descricoes, como faz o quickstart

## Padrao canônico do Service

O service padrão deve estender `AbstractBaseCrudService<E, D, ID, FD>`.

Template minimo:

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

Quando houver relacionamentos `@ManyToOne` ou `@OneToOne`, `mergeUpdate(...)` deve preservar a semantica do relacionamento no aggregate persistido.

## Padrao canônico do Mapper

O padrao preferencial publicado hoje e MapStruct com `CorporateMapperConfig`.

Template:

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

- prefira `CorporateMapperConfig` quando o projeto usar MapStruct, porque esse e o padrao operacional mais consistente no ecossistema atual
- use mapper manual apenas quando isso for realmente mais simples e o projeto local adotar esse estilo
- ao mapear relacoes por ID, produza `relacaoId` no DTO e reconstrua a referencia por ID na volta

## DTO canônico

O DTO deve refletir o contrato que a UI vai consumir via `/schemas/filtered`.

Regras obrigatorias:

- campos visiveis na UI devem usar `@UISchema`
- validacoes estruturais devem usar Bean Validation
- relacoes para selects devem usar `endpoint`, `valueField` e `displayField` coerentes com o endpoint real

Exemplo alinhado ao quickstart:

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

Regra critica:

- se o endpoint for `.../options/filter`, use `displayField = "label"`
- se o endpoint for `.../filter` retornando DTO completo, use o campo textual do DTO, como `nome`

## FilterDTO canônico

O `FilterDTO` deve implementar `GenericFilterDTO` e modelar criterios de busca reais.

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
- `FieldControlType.TOGGLE` existe, mas no quickstart varios booleanos usam `CHECKBOX`
- `FieldControlType.TOGGLE_SWITCH` nao e canonico

## Endpoints reais que a UI espera

O contrato minimo do recurso deve ser compatível com:

- `GET /{resource}/schemas`
- `GET /schemas/filtered?path={resource}/all&operation=get&schemaType=response`
- `GET /schemas/filtered?path={resource}/filter&operation=post&schemaType=request`
- `POST /{resource}/filter`
- `POST /{resource}/filter/cursor`
- `POST /{resource}/locate`
- `POST /{resource}/options/filter`
- `GET /{resource}/options/by-ids`
- CRUD basico

## Como o Angular realmente consome isso

`praxis-ui-angular` usa `GenericCrudService` para:

- chamar `getSchema()` no recurso e, a partir disso, resolver o grid schema estrutural via `/schemas/filtered`
- chamar `getFilteredSchema()` para o `FilterDTO`
- revalidar com `If-None-Match`
- ler `ETag`, `X-Schema-Hash` e `x-ui.resource.idField`

Implicacoes para o codigo gerado:

- o schema precisa expor `x-ui` coerente
- o DTO precisa manter `idField` inferivel ou sobrescrito no controller
- endpoints de select precisam ser compativeis com `OptionDTO{id,label}`

## Uso real de referencia

O recurso `Funcionario` do `praxis-api-quickstart` e a melhor referencia operacional atual para um CRUD completo:

- controller sobre `AbstractCrudController`
- `options/filter` retornando `OptionDTO`
- DTO com `displayField = "label"` para selects remotos
- mapper com `CorporateMapperConfig`

## Prompt recomendado para IA

```text
Voce esta gerando uma feature CRUD metadata-driven para o ecossistema Praxis.

Siga o contrato canônico do praxis-metadata-starter.
Use praxis-api-quickstart como host operacional de referencia e praxis-ui-angular como consumidor final do contrato.

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

## Checklist minimo de saida

Antes de concluir, o agente deve conferir:

- o controller usa `@ApiResource` e `@ApiGroup`
- o mapper usa `CorporateMapperConfig` quando aplicavel
- `FilterOperation` usa enums canônicos
- selects remotos apontam para `/options/filter` com `displayField = "label"` quando consumirem `OptionDTO`
- o recurso pode ser consumido por `GenericCrudService` sem adaptacao local

## Fora de escopo

Este guia nao institucionaliza:

- templates antigos baseados em `ms-pessoa-ananke`
- suposicao de que toda entidade precisa de bulk
- dependencia obrigatoria em modulos externos nao publicados neste starter

## Referencias cruzadas

- `praxis-metadata-starter/README.md`
- `praxis-api-quickstart/README.md`
- `praxis-ui-angular/projects/praxis-core/src/lib/services/generic-crud.service.ts`
- `praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr/controller/FuncionarioController.java`
- `praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr/dto/FuncionarioDTO.java`
