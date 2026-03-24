# Prompts de Execucao da Prova

Use estes prompts sem acrescentar instrucoes corretivas fora do que os guias oficiais ja publicam.

## Prompt Base

```text
Voce vai executar uma prova operacional dos guias de IA do praxis-metadata-starter.

Use apenas estes materiais como fonte principal:
- docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md
- docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md
- docs/guides/CHECKLIST-VALIDACAO-IA.md

Objetivo:
- criar a aplicacao
- implementar o CRUD pedido
- compilar
- subir
- validar os endpoints e schemas exigidos

Regras:
- nao use bulk como baseline
- nao copie codigo manualmente do praxis-api-quickstart
- nao invente contratos fora do starter
- se usar MapStruct, configure-o de forma compilavel
- siga o contrato metadata-driven consumivel por praxis-ui-angular

Ao final, entregue:
- lista dos arquivos criados
- comandos executados
- resultado de build
- evidencias dos endpoints validados
- falhas encontradas
```

## Rodada 1: H2 simples

```text
Execute a prova operacional usando H2 local.

Cenário:
- Nome da aplicacao: ai-guide-proof-h2-simple
- GroupId: com.example.proof
- ArtifactId: ai-guide-proof-h2-simple
- Pacote base: com.example.proof.catalog
- Resource path: /api/catalog/categorias
- Api group: catalog

Entidade inicial:
- Categoria
- Campos:
  - id: Integer
  - nome: String
  - ativo: Boolean

Exigencias:
- usar banco H2 local
- incluir persistencia real
- incluir bootstrap fisico do schema para a entidade inicial
- preferir migration inicial; usar `ddl-auto` apenas se deixar isso explicito como contingencia de sandbox local
- expor CRUD baseline
- gerar DTO com @UISchema
- gerar FilterDTO com @Filterable
- publicar /schemas/filtered
- validar request schema e response schema

Nao adicione relacoes nesta rodada.
```

## Rodada 2: H2 com relacao

```text
Execute a prova operacional usando H2 local com relacao simples.

Cenário:
- Nome da aplicacao: ai-guide-proof-h2-relation
- GroupId: com.example.proof
- ArtifactId: ai-guide-proof-h2-relation
- Pacote base: com.example.proof.catalog
- Recursos:
  - /api/catalog/categorias
  - /api/catalog/produtos
- Api group: catalog

Entidades:
- Categoria:
  - id: Integer
  - nome: String
  - ativo: Boolean
- Produto:
  - id: Integer
  - nome: String
  - ativo: Boolean
  - categoria: ManyToOne para Categoria

Exigencias:
- o DTO de Produto deve expor categoriaId
- selects remotos devem usar /options/filter
- quando o endpoint retornar OptionDTO, use displayField=label
- validar schema request/response e endpoints de options
```

## Rodada 3: PostgreSQL simples

```text
Execute a prova operacional usando PostgreSQL local.

Cenário:
- Nome da aplicacao: ai-guide-proof-pg-simple
- GroupId: com.example.proof
- ArtifactId: ai-guide-proof-pg-simple
- Pacote base: com.example.proof.catalog
- Resource path: /api/catalog/categorias
- Api group: catalog

Entidade inicial:
- Categoria
- Campos:
  - id: Integer
  - nome: String
  - ativo: Boolean

Exigencias:
- configurar datasource PostgreSQL real
- incluir Flyway
- compilar, subir e persistir no banco
- validar os schemas e endpoints do contrato
```

## Rodada 4: PostgreSQL com relacao

```text
Execute a prova operacional usando PostgreSQL local com relacao simples.

Cenário:
- Nome da aplicacao: ai-guide-proof-pg-relation
- GroupId: com.example.proof
- ArtifactId: ai-guide-proof-pg-relation
- Pacote base: com.example.proof.catalog
- Recursos:
  - /api/catalog/categorias
  - /api/catalog/produtos
- Api group: catalog

Entidades:
- Categoria:
  - id: Integer
  - nome: String
  - ativo: Boolean
- Produto:
  - id: Integer
  - nome: String
  - ativo: Boolean
  - categoria: ManyToOne para Categoria

Exigencias:
- Flyway funcional
- options/filter funcional para Categoria
- Produto consumivel por UI metadata-driven sem adaptacao local
- validar request schema, response schema, ETag e X-Schema-Hash
```

## Rodada 5: Consumo Angular

```text
Use o backend gerado e valide o consumo pelo GenericCrudService do praxis-ui-angular.

Objetivo:
- resolver getSchema()
- resolver /schemas/filtered para grid e filtro
- verificar idField
- verificar revalidacao com If-None-Match

Nao altere o contrato do backend apenas para acomodar workaround local de frontend.
Se houver falha, registre se a causa e do backend gerado, do guia ou do runtime consumidor.
```
