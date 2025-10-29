# PR — Fase 4: Services, OptionDTO e Ordenação Padrão

Título sugerido: Auditoria Fase 4 — Services Base, @OptionLabel, OptionDTO e @DefaultSortColumn

Descrição
- Garante contratos de service, projeção de opções performática e ordenação padrão estável.

Escopo
- `BaseCrudService`/`AbstractBaseCrudService`
- `OptionDTO`/`getOptionMapper()` e `@OptionLabel`
- `@DefaultSortColumn` e `getDatasetVersion()`

Checklist de Aceite
- [ ] Services implementam/estendem os contratos base corretamente
- [ ] Projeções de opções corretas (id/label) e precisas
- [ ] Campos anotados com `@DefaultSortColumn` (prioridade/direção)
- [ ] `getDatasetVersion()` implementado quando aplicável

Evidências
- Referências de arquivos (`path:line`) dos services e entidades
- Diffs para @OptionLabel, `getOptionMapper()` custom e `@DefaultSortColumn`
- Exemplo de resposta com `X-Data-Version` (se disponível)

Configurações alteradas (se houver)
- 

Riscos e rollback
- Mudanças de ordenação podem impactar consultas; rollback: remover/ajustar anotações de sort

Passos de revisão/teste
- Exercitar endpoints de options e listar/filtrar observando a ordenação

Fora de escopo
- Alterações de domínio além de mapeamentos e anotações

Referências
- `src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseCrudService.java:1`
- `src/main/java/org/praxisplatform/uischema/service/base/BaseCrudService.java:40`
- `src/main/java/org/praxisplatform/uischema/annotation/OptionLabel.java:1`
- `src/main/java/org/praxisplatform/uischema/service/base/annotation/DefaultSortColumn.java:118`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs revisados
- [ ] Funcionalidade validada em endpoints
