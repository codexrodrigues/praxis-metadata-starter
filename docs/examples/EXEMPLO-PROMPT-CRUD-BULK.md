# Exemplo de Prompt para Solicitar Recurso CRUD Metadata-Driven

Este arquivo mostra um prompt recomendado para agentes de IA gerarem um recurso alinhado ao `GUIA-CLAUDE-AI-CRUD-BULK.md`.

## Template recomendado

```text
Crie um recurso CRUD metadata-driven alinhado ao praxis-metadata-starter.

Siga o guia GUIA-CLAUDE-AI-CRUD-BULK.md.
Use praxis-api-quickstart como host operacional de referencia.
Garanta compatibilidade de consumo com praxis-ui-angular.

Entrada:
- Entidade: [caminho-da-entidade]
- Resource path: [path-do-recurso]
- Api group: [grupo-openapi]
- Pacote base: [pacote-java]

Saída esperada:
- DTO com @UISchema e Bean Validation
- FilterDTO com @Filterable
- Mapper
- Repository
- Service
- Controller

Nao trate bulk como obrigatorio.
So adicione trilha bulk se eu pedir explicitamente.
```

## Exemplo real alinhado ao quickstart

```text
Crie um recurso CRUD metadata-driven alinhado ao praxis-metadata-starter.

Siga o guia GUIA-CLAUDE-AI-CRUD-BULK.md.
Use praxis-api-quickstart como host operacional de referencia.
Garanta compatibilidade de consumo com praxis-ui-angular.

Entrada:
- Entidade: src/main/java/com/example/praxis/apiquickstart/hr/entity/Funcionario.java
- Resource path: /api/human-resources/funcionarios
- Api group: human-resources
- Pacote base: com.example.praxis.apiquickstart.hr

Saída esperada:
- DTO com @UISchema e Bean Validation
- FilterDTO com @Filterable
- Mapper com CorporateMapperConfig
- Repository
- Service
- Controller

Nao trate bulk como obrigatorio.
```

## Variante para nova entidade de catálogo simples

```text
Crie um recurso CRUD metadata-driven alinhado ao praxis-metadata-starter.

Entrada:
- Entidade: src/main/java/com/example/demo/catalog/entity/Categoria.java
- Resource path: /api/catalog/categorias
- Api group: catalog
- Pacote base: com.example.demo.catalog

Regras adicionais:
- Seletor remoto deve usar /options/filter com displayField=label quando consumir OptionDTO
- Se usar MapStruct, adote CorporateMapperConfig
- O recurso precisa ser consumivel por GenericCrudService sem adaptacao local
```

## O que o prompt deve evitar

Nao peça ao agente para assumir automaticamente:

- `BulkFilterAdapter`
- `BulkController`
- dependencias `org.praxisplatform.bulk.*`
- exemplos herdados de `ms-pessoa-ananke`

## Resposta esperada do agente

Uma resposta boa tende a:

1. classificar a entidade e os relacionamentos
2. gerar os arquivos mínimos do recurso
3. justificar selects remotos com `/options/filter`
4. usar `displayField=label` quando o endpoint retorna `OptionDTO`
5. citar a compatibilidade com `/schemas/filtered` e `GenericCrudService`

## Referências

- `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
- `praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr/controller/FuncionarioController.java`
- `praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr/dto/FuncionarioDTO.java`
- `praxis-ui-angular/projects/praxis-core/src/lib/services/generic-crud.service.ts`
