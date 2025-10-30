# PR — Fase 8: Auto‑config, Grupos de Infra/Fallback e BigDecimal

Título sugerido: Auditoria Fase 8 — Auto‑configs, grupos fixos e BigDecimal → number/decimal

Descrição
- Confirma auto‑configs ativas, grupos fixos (`praxis-metadata-infra`, `application`) e mapeamento BigDecimal coerente no OpenAPI.

Escopo
- Auto‑configs detectadas em runtime
- Grupos fixos presentes no Swagger UI
- BigDecimal mapeado para `number/decimal`

Checklist de Aceite
- [ ] `PraxisMetadataAutoConfiguration` e `OpenApiUiSchemaAutoConfiguration` carregadas
- [ ] `praxis-metadata-infra` e `application` visíveis e corretos
- [ ] Campos BigDecimal documentados como `type:number`, `format:decimal`

Evidências
- Logs com `--debug` exibindo auto‑configs
- Prints/links do Swagger UI (dropdown de grupos)
- Trecho de schema exibindo BigDecimal

Configurações alteradas (se houver)
- Qualifiers/Primary para beans customizados

Riscos e rollback
- Conflitos de beans; rollback: remover bean customizado ou ajustar `@Primary`

Passos de revisão/teste
- Executar app e validar grupos na UI; inspecionar schema

Fora de escopo
- Alterações de endpoints de negócio

Referências
- `src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:1`
- `src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:177`
- `src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:240`
- `docs/technical/AUTO-CONFIGURACAO.md:1`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs/configs revisados
- [ ] Validado no ambiente de destino
