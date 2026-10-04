# Documentacao Praxis Metadata Starter

Este diretorio contem a documentacao principal do `praxis-metadata-starter`.

A rc.154 pública inclui a seleção QUERY delimitada a `UNIFORM_UPDATE/SYNC/PER_ITEM`, com `ReadyAdmission`, captura protegida e reserva governada; consulte [armazenamento](spec/BULK-PROPOSAL-STORAGE.md), [lifecycle](spec/BULK-OPERATION-LIFECYCLE.md) e [execução](spec/BULK-DURABLE-EXECUTION.md). O workflow oficial 37236119846 passou com 1.530 testes, zero falhas/erros e três skips, e POM/JAR estão verificados no Maven Central. O verify e o aceite de adoção do host rc.154 ainda estão em curso.

## Estrutura resumida

- `guides/` - trilha principal para LLMs e implementacao passo a passo
- `overview/` - visao geral funcional para novos usuarios
- `concepts/` - conceitos e semantica do contrato metadata-driven
- `examples/` - exemplos complementares
- `technical/` - referencia tecnica adicional

## Ponto de entrada por objetivo

- trilha principal para LLM: [guides/README.md](guides/README.md)
- visao geral funcional: [overview/VISAO-GERAL.md](overview/VISAO-GERAL.md)
- arquitetura e fluxo de enriquecimento: [architecture-overview.md](architecture-overview.md)
- responsabilidade de cada pacote Java: [packages-overview.md](packages-overview.md)
- conceito de anotacoes Java para metadata de UI: [concepts/ui-schema.md](concepts/ui-schema.md)
- contrato de exportacao de colecoes: [guides/COLLECTION-EXPORT.md](guides/COLLECTION-EXPORT.md)
- prova operacional dos guias: [guides/ai-proof/README.md](guides/ai-proof/README.md)
- exemplos complementares: [examples/README.md](examples/README.md)
- referencia tecnica publica complementar: [GitHub Pages / apidocs](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)

## Hierarquia canonica

- `guides/` define a trilha principal de leitura para execucao por LLM
- `guides/ai-proof/` define como provar que os guias sao suficientes
- `apidocs/` e apoio tecnico fino, nao a trilha principal
- `examples/`, `concepts/` e `technical/` aprofundam temas especificos

## Criterio de qualidade

Esta documentacao deve permanecer aderente a:

- codigo canonico do `praxis-metadata-starter`
- contrato realmente consumido pelo runtime Angular oficial da plataforma
- protocolo de prova publicado em `docs/guides/ai-proof/`
