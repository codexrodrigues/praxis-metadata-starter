# Fase 4 — Services, Repositórios, OptionDTO e Ordenação Padrão

Objetivo: garantir contratos de serviço completos, projeções de opções corretas e ordenação padrão estável.

Saídas esperadas:

- Lista de services com confirmação de implementação `BaseCrudService`/`AbstractBaseCrudService`
- Evidência de `OptionDTO` funcionando (via `getId()` na entidade ou `getOptionMapper()` sobrescrito)
- Aplicação de `@OptionLabel` no campo/getter de rótulo preferido
- `@DefaultSortColumn` aplicado nos campos de ordenação padrão e, quando relevante, implementação de `getDatasetVersion()`

Checklist

- Services expõem `getRepository()`, `getSpecificationsBuilder()` e `getEntityClass()` (ou herdam de `AbstractBaseCrudService`)
- Projeções de opções: `getOptionMapper()` default suficiente OU sobrescrito quando necessário
- `@OptionLabel` presente para desempenho e precisão do label
- `@DefaultSortColumn` define ordenação padrão utilizada em `findAll` e `filter`
- `getDatasetVersion()` implementado quando o cliente se beneficia de cache por versão

Verificações e evidências

- Inspecionar services e entidades quanto a `getId()`
- Verificar existência/uso de `@OptionLabel` e ordenação anotada
- Confirmar se o controller retorna cabeçalho `X-Data-Version` quando disponível

Correções comuns

- Adicionar `@OptionLabel` para evitar heurística de label
- Sobrescrever `getOptionMapper()` para labels compostos ou `extra`
- Anotar campos com `@DefaultSortColumn(priority=...)`
- Implementar `getDatasetVersion()` (ex.: a partir de `updatedAt`)

Referências rápidas

- Service base: src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseCrudService.java:1
- Contrato base (OptionMapper, default sort, datasetVersion): src/main/java/org/praxisplatform/uischema/service/base/BaseCrudService.java:40
- OptionDTO: src/main/java/org/praxisplatform/uischema/dto/OptionDTO.java:1
- `@OptionLabel`: src/main/java/org/praxisplatform/uischema/annotation/OptionLabel.java:1
- `@DefaultSortColumn`: src/main/java/org/praxisplatform/uischema/service/base/annotation/DefaultSortColumn.java:118

Prompt para agente

```
Tarefa: Auditar services, projeções de opções e ordenação padrão.

Passos:
1) Mapeie services e confirme implementação BaseCrudService/AbstractBaseCrudService
2) Valide OptionDTO: existe getId()? Se não, sobrescreva getOptionMapper
3) Aplique @OptionLabel no label ideal
4) Configure @DefaultSortColumn nos campos de ordenação padrão
5) Avalie implementar getDatasetVersion() (para X-Data-Version e cache cliente)

Entregue:
- Lista de services com status e gaps
- Diffs sugeridos para getOptionMapper/@OptionLabel/@DefaultSortColumn/getDatasetVersion
```

