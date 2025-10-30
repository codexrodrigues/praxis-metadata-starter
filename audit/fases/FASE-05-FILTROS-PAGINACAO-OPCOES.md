# Fase 5 — Filtros, Paginação e Endpoints de Opções

Objetivo: filtros tipados, limites seguros e endpoints de opções funcionando corretamente.

Saídas esperadas:

- Inventário de Filter DTOs (que estendem `GenericFilterDTO`) com campos `@Filterable`
- Verificação de limites: `praxis.pagination.max-size` e `praxis.query.by-ids.max`
- Endpoints de opções disponíveis: `/options/filter` e `/options/by-ids`

Checklist

- Filter DTOs anotados com `@Filterable` nos campos filtráveis; `relation` para joins quando aplicável
- UI dos filtros anotada com `@UISchema` em cada campo filtrável, seguindo padrões do starter:
  - Texto (LIKE): `@UISchema`
  - Numéricos (BETWEEN): `@UISchema(type=NUMBER, controlType=RANGE_SLIDER, numericFormat/numericStep quando moeda)`
  - Datas (BETWEEN): `@UISchema(type=DATE, controlType=DATE_RANGE)`
  - Booleanos (EQUAL): `@UISchema(type=BOOLEAN, controlType=CHECKBOX)`
  - Relações (EQUAL + relation): `@UISchema(type=NUMBER, controlType=SELECT, endpoint=ApiPaths.<Modulo>.<Recurso> + "/filter")`
- Endpoints `POST /{resource}/filter` respeitam limite de `size`
- `GET /{resource}/by-ids` respeita limite máximo configurado de IDs
- Projeções de opções: `POST /{resource}/options/filter` e `GET /{resource}/options/by-ids` operacionais

Seleção vs Listagem — qual endpoint usar

- `POST /{resource}/options/filter` (para SELECT/MULTI_SELECT/AUTOCOMPLETE)
  - Usa o mesmo `FilterDTO` do recurso e retorna `OptionDTO {id,label,extra}`
  - Recomendado para popular combos com paginação e busca por nome/código
  - Configure no `@UISchema` do campo: `endpoint` → `/{resource}/options/filter`, `valueField="id"`, `displayField="label"`
  - Respeita `praxis.pagination.max-size`
- `GET /{resource}/options/by-ids` (reidratar opções existentes)
  - Dados iniciais de selects/chips; preserva a ordem de entrada
  - Respeita `praxis.query.by-ids.max`
- `POST /{resource}/filter` (listagens “ricas” de DTOs)
  - Para tabelas/grades com múltiplas colunas e atributos completos
  - Retorna o DTO completo, não `OptionDTO`

Verificações e evidências

- Checar DTOs de filtro e suas anotações (@Filterable)
- Checar `@UISchema` nos FilterDTOs e o `controlType`/`type` coerente com o tipo do campo
- Para relações, verificar `endpoint` apontando para `/{resource}/options/filter` e `relation="entidade.id"`
- Confirmar uso do `GenericSpecificationsBuilder` (join por `relation` e respeito a `Sort`)
- Validar no controller base o lançamento de 422 quando os limites forem excedidos (se aplicável)

Correções comuns

- Adicionar `@Filterable` aos campos sem anotações
- Preencher `relation` com caminho `relacao1.relacao2.campo` onde necessário
- Adicionar `@UISchema` nos campos de filtro com os seguintes padrões mínimos:
  - Datas BETWEEN → `@UISchema(type=DATE, controlType=DATE_RANGE)`
  - Números BETWEEN → `@UISchema(type=NUMBER, controlType=RANGE_SLIDER)` (se moeda, `numericFormat=CURRENCY`, `numericStep="0.01"`)
  - Boolean EQUAL → `@UISchema(type=BOOLEAN, controlType=CHECKBOX)`
  - Relações EQUAL → `@UISchema(type=NUMBER, controlType=SELECT, endpoint="/{resource}/options/filter")`
- Ajustar propriedades de limite por ambiente, se o domínio exigir

Referências rápidas

- Anotação: src/main/java/org/praxisplatform/uischema/filter/annotation/Filterable.java:71
- Builder: src/main/java/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.java:21
- Controller base (limites e opções): src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:188

Prompt para agente

```
Tarefa: Auditar filtros, paginação e endpoints de opções.

Passos:
1) Mapeie FilterDTOs e valide o uso de @Filterable (operation e relation para joins)
1.1) Verifique @UISchema por campo de filtro, com controlType/type apropriados (DATE_RANGE/RANGE_SLIDER/CHECKBOX)
1.2) Para campos relacionais de SELECT/MULTI_SELECT, confirme `endpoint=/{resource}/options/filter`, `valueField="id"`, `displayField="label"`
2) Confirme limites de paginação e by-ids (praxis.pagination.max-size e praxis.query.by-ids.max)
3) Verifique as rotas /{resource}/options/filter e /{resource}/options/by-ids

Entregue:
- Lista de FilterDTOs e gaps (campos sem @Filterable; relations faltando)
- Gaps de UI (campos de filtro sem @UISchema ou com controlType inadequado)
- Diffs para adicionar @Filterable ou relations necessárias
- Diffs para adicionar @UISchema conforme padrões de UI e uso correto de `/options/filter` e `/options/by-ids`
- Ajustes de propriedades de limite, se pertinente
```

Operações e tipos (guia rápido)

- EQUAL: tipos simples (String, Long, LocalDate)
- LIKE: String (usa `%valor%`)
- GREATER_THAN / LESS_THAN: numéricos e datas
- IN: coleção (List/Set) com os valores alvo
- BETWEEN: List com 2 elementos (min, max) de numéricos/datas

Exemplos práticos (seed)

- ProductFilterDTO: examples/praxis-backend-seed-app/src/main/java/com/example/praxisseed/dto/filter/ProductFilterDTO.java:1
  - LIKE em `name`, BETWEEN em `priceRange`, min/max em `minPrice`/`maxPrice`
  - LIKE com `relation = "category.name"`
  - EQUAL/MULTI_SELECT em `categoryId`/`categoryIds` com endpoint de opções de categorias
  - IN em `ids` do próprio recurso
- CategoryFilterDTO: examples/praxis-backend-seed-app/src/main/java/com/example/praxisseed/dto/filter/CategoryFilterDTO.java:1
  - LIKE em `name`/`description` e IN em `ids` (multi-select)

Exemplo (padrão mínimo) — data (intervalo), relacionamento e options

```java
// Data (intervalo)
@UISchema(type = FieldDataType.DATE, controlType = FieldControlType.DATE_RANGE)
@Filterable(operation = Filterable.FilterOperation.BETWEEN)
private List<LocalDate> dataPagamentoBetween;

// Relação (SELECT + relation)
@UISchema(type = FieldDataType.NUMBER, controlType = FieldControlType.SELECT,
          endpoint = "/api/human-resources/funcionarios/options/filter",
          valueField = "id", displayField = "label")
@Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "funcionario.id")
private Integer funcionarioId;

// Reidratação de opções já selecionadas
// GET /api/human-resources/funcionarios/options/by-ids?ids=1&ids=3
```
