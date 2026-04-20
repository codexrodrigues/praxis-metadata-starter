# Documentacao Praxis Metadata Starter

Este diretorio contem a documentacao principal do `praxis-metadata-starter`.

## Estrutura resumida

- `guides/` - trilha principal para LLMs e implementacao passo a passo
- `overview/` - visao geral funcional para novos usuarios
- `concepts/` - conceitos e semantica do contrato metadata-driven
- `examples/` - exemplos complementares
- `technical/` - referencia tecnica adicional

## Ponto de entrada por objetivo

- trilha principal para LLM: [guides/README.md](guides/README.md)
- visao geral funcional: [overview/VISAO-GERAL.md](overview/VISAO-GERAL.md)
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
