# Fase 8 — Auto‑configuração, Grupos de Infra/Fallback e BigDecimal

Objetivo: auto‑config ativa, grupos fixos expostos e mapeamento BigDecimal coerente no OpenAPI.

Saídas esperadas:

- Evidência de que as auto‑configs estão ativas e detectadas
- Grupos `praxis-metadata-infra` (/schemas/**) e `application` (fallback) disponíveis
- Confirmação de BigDecimal → `type:number`, `format:decimal` no OpenAPI

Checklist

- `PraxisMetadataAutoConfiguration` e `OpenApiUiSchemaAutoConfiguration` carregadas
- Grupo `praxis-metadata-infra` presente e separado da aplicação
- Grupo `application` atuando como fallback (sem conflitar com grupos dinâmicos)
- `NumberSchema` aplicado para `BigDecimal` (replaceWithSchema)

Verificações e evidências

- Rodar com `--debug` e capturar logs de auto‑config
- Validar grupos no Swagger UI (quando disponível)
- Checar um schema com campo BigDecimal para confirmar o formato

Correções comuns

- Ajustar classpath/boot para incluir as auto‑configs
- Resolver conflitos de beans com `@Primary`/`@Qualifier`

Referências rápidas

- Auto‑config: src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:1
- BigDecimal customizer: src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:177
- Infra/fallback groups: src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:240
- Guia: docs/technical/AUTO-CONFIGURACAO.md:1

Prompt para agente

```
Tarefa: Auditar auto‑configuração, grupos fixos e BigDecimal.

Passos:
1) Confirme que as auto‑configs e grupos fixos (praxis-metadata-infra, application) estão ativos
2) Valide o mapeamento BigDecimal→number/decimal em um schema OpenAPI do projeto
3) Se houver falhas de detecção, proponha ajustes de configuração/DI

Entregue:
- Evidências (logs, prints) dos grupos no Swagger UI
- Diffs ou instruções de configuração para corrigir auto‑config ou DI
```

