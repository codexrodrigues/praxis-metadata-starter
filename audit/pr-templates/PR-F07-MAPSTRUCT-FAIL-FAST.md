# PR — Fase 7: MapStruct (Fail‑Fast)

Título sugerido: Auditoria Fase 7 — CorporateMapperConfig e correções de unmapped

Descrição
- Assegura uso de `CorporateMapperConfig` (componentModel=spring, unmappedTargetPolicy=ERROR) e corrige campos não mapeados.

Escopo
- Verificar e padronizar `@Mapper(..., config = CorporateMapperConfig.class)`
- Corrigir unmapped com `@Mapping`

Checklist de Aceite
- [ ] Todos mappers usam `CorporateMapperConfig`
- [ ] Build sem erros de unmapped

Evidências
- Lista de mappers e referências `path:line`
- Diffs com `@Mapper`/`@Mapping` adicionados
- Log de build pós‑correções

Configurações alteradas (se houver)
- 

Riscos e rollback
- Risco baixo (ajustes de mapeamento); rollback: reverter diffs conflitantes

Passos de revisão/teste
- Rodar build e garantir ausência de unmapped

Fora de escopo
- Mudanças de contratos de DTO além do necessário

Referências
- `src/main/java/org/praxisplatform/uischema/mapper/config/CorporateMapperConfig.java:1`
- `README.md:42`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs revisados
- [ ] Build verde
