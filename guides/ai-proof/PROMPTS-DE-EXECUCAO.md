# Prompts de Execucao da Prova

Use estes prompts sem acrescentar instrucoes corretivas fora do que os guias
oficiais ja publicam.

## Prompt base

```text
Voce vai executar uma prova operacional dos guias de IA do praxis-metadata-starter.

Use apenas estes materiais como fonte principal:
- docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md
- docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md
- docs/guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md
- docs/guides/CHECKLIST-VALIDACAO-IA.md

Objetivo:
- criar a aplicacao
- implementar o CRUD pedido
- compilar
- subir
- validar os endpoints e schemas exigidos

Regras:
- nao use bulk como baseline
- nao consulte app externo como fonte necessaria de implementacao
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

Cenario:
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
```

## Rodada 2: H2 com relacao

```text
Execute a prova operacional usando H2 local com relacao simples.

Cenario:
- Nome da aplicacao: ai-guide-proof-h2-relation
- GroupId: com.example.proof
- ArtifactId: ai-guide-proof-h2-relation
- Pacote base: com.example.proof.catalog
- Recursos:
  - /api/catalog/categorias
  - /api/catalog/produtos
- Api group: catalog
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
```

## Rodada 6: Frontend Angular completo

```text
Use o backend ja aprovado como fonte do contrato.

Use apenas estes materiais como fonte principal:
- docs/guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md
- docs/guides/CHECKLIST-VALIDACAO-IA.md

Objetivo:
- gerar o host Angular canonico de CRUD completo
- usar @praxisui/crud como shell principal
- usar @praxisui/dynamic-form para view/edit quando houver rota
- alinhar resourcePath, idField, API_URL e GenericCrudService ao runtime real
```
