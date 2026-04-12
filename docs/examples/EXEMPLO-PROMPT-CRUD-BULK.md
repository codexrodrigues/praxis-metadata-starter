# Exemplo de Prompt para Solicitar Recurso Metadata-Driven

Este arquivo mostra um prompt recomendado para agentes de IA gerarem um recurso
alinhado ao [Guia 02](../guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md).

## Template recomendado

```text
Crie um recurso metadata-driven alinhado ao baseline atual do praxis-metadata-starter.

Siga o guia GUIA-02-AI-BACKEND-CRUD-METADATA.md.
Garanta alinhamento de consumo com praxis-ui-angular.

Entrada:
- Entidade: [caminho-da-entidade]
- Resource path: [path-do-recurso]
- Api group: [grupo-openapi]
- Pacote base: [pacote-java]

Saida esperada:
- DTO com @UISchema e Bean Validation
- FilterDTO com @Filterable
- Mapper
- Repository
- Service
- Controller

Nao trate bulk como obrigatorio.
So adicione trilha bulk se eu pedir explicitamente.
```

## Exemplo autocontido

```text
Crie um recurso metadata-driven alinhado ao baseline atual do praxis-metadata-starter.

Siga o guia GUIA-02-AI-BACKEND-CRUD-METADATA.md.
Garanta alinhamento de consumo com praxis-ui-angular.

Entrada:
- Entidade: src/main/java/com/example/demo/hr/entity/Funcionario.java
- Resource path: /api/human-resources/funcionarios
- Api group: human-resources
- Pacote base: com.example.demo.hr

Saida esperada:
- DTO com @UISchema e Bean Validation
- FilterDTO com @Filterable
- Mapper com CorporateMapperConfig
- Repository
- Service
- Controller

Nao trate bulk como obrigatorio.
```

## Variante para entidade de catalogo simples

```text
Crie um recurso metadata-driven alinhado ao baseline atual do praxis-metadata-starter.

Entrada:
- Entidade: src/main/java/com/example/demo/catalog/entity/Categoria.java
- Resource path: /api/catalog/categorias
- Api group: catalog
- Pacote base: com.example.demo.catalog

Regras adicionais:
- seletor remoto deve usar /options/filter com displayField=label quando consumir OptionDTO
- se usar MapStruct, adote CorporateMapperConfig
- o recurso precisa ser consumivel por GenericCrudService sem adaptacao local
```

## O que o prompt deve evitar

Nao peca ao agente para assumir automaticamente:

- `BulkFilterAdapter`
- `BulkController`
- dependencias `org.praxisplatform.bulk.*`
- exemplos provenientes de stacks externas

## Resposta esperada do agente

Uma resposta boa tende a:

1. classificar a entidade e os relacionamentos
2. gerar os arquivos minimos do recurso
3. justificar selects remotos com `/options/filter`
4. usar `displayField=label` quando o endpoint retorna `OptionDTO`
5. citar o alinhamento com `/schemas/filtered` e `GenericCrudService`

## Referencias publicas

- `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- repositório público do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacote publicado de consumo de contrato: `@praxisui/core`
