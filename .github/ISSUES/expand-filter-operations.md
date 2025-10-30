---
title: "Feat: Expand Filter Operations (NOT_EQUAL, GTE/LTE, NOT_LIKE, STARTS/ENDS, NOT_IN, NULL checks)"
labels: [enhancement, filters]
assignees: []
---

Contexto
- Hoje o mecanismo de filtros suporta: EQUAL, LIKE, GREATER_THAN, LESS_THAN, IN, BETWEEN via `@Filterable` + builders JPA.
- Há demandas recorrentes por operações adicionais com o mesmo padrão de implementação.

Objetivo
- Ampliar as operações suportadas, mantendo o mecanismo atual (enum + PredicateBuilder) e a compatibilidade.

Escopo
- Adicionar as operações abaixo em `@Filterable.FilterOperation` e respectivos PredicateBuilders:
  - NOT_EQUAL
  - GREATER_OR_EQUAL
  - LESS_OR_EQUAL
  - NOT_LIKE (case-insensitive)
  - STARTS_WITH (case-insensitive)
  - ENDS_WITH (case-insensitive)
  - NOT_IN
  - IS_NULL
  - IS_NOT_NULL

Pontos de Código
- Enum de operações: `src/main/java/org/praxisplatform/uischema/filter/annotation/Filterable.java`
- Registro e implementação de builders: `src/main/java/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.java`
  - Interface `PredicateBuilder`
  - Lista `predicateBuilders`

Proposta de Implementação
1) Enum
   - Incluir os novos valores no enum `FilterOperation`.

2) Builders (novas classes internas ao arquivo, seguindo o padrão existente):
   - NotEqualPredicateBuilder
     - supports → NOT_EQUAL
     - build → `criteriaBuilder.notEqual(path, value)`
   - GreaterOrEqualPredicateBuilder
     - supports → GREATER_OR_EQUAL
     - build → `criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) path, (Comparable) value)`
   - LessOrEqualPredicateBuilder
     - supports → LESS_OR_EQUAL
     - build → `criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) path, (Comparable) value)`
   - NotLikePredicateBuilder
     - supports → NOT_LIKE
     - build → `criteriaBuilder.not(criteriaBuilder.like(lower(path), pattern))` (usar lower-case para case-insensitive)
   - StartsWithPredicateBuilder
     - supports → STARTS_WITH
     - build → `criteriaBuilder.like(lower(path), lower(value) + "%")`
   - EndsWithPredicateBuilder
     - supports → ENDS_WITH
     - build → `criteriaBuilder.like(lower(path), "%" + lower(value))`
   - NotInPredicateBuilder
     - supports → NOT_IN
     - build → `criteriaBuilder.not(criteriaBuilder.in(path).value(...))`
   - IsNullPredicateBuilder
     - supports → IS_NULL
     - build → `criteriaBuilder.isNull(path)`
   - IsNotNullPredicateBuilder
     - supports → IS_NOT_NULL
     - build → `criteriaBuilder.isNotNull(path)`

3) Registro
   - Incluir os novos builders na lista `predicateBuilders = List.of(...)` mantendo a ordem consistente.

4) IS_NULL/IS_NOT_NULL (escolha da abordagem)
   - A) Sem alterar o fluxo atual: o DTO expõe `Boolean isNullX` / `Boolean isNotNullX`; quando true, aplica o filtro. O builder valida `value == Boolean.TRUE` antes de construir o predicado.
   - B) Pequeno ajuste em `processField`: obter `Filterable` antes do `value != null` e permitir processar operações de NULL mesmo com `value == null`.
   - Sugerido: (A) por ser não-invasivo; (B) é simples também, mas altera o fluxo. Deixar registrado em comentário.

Testes
- Criar testes unitários dirigidos para cada operação:
  - NOT_EQUAL: String/UUID
  - GREATER_OR_EQUAL / LESS_OR_EQUAL: numérico (Long/BigDecimal) e data (se aplicável)
  - NOT_LIKE / STARTS_WITH / ENDS_WITH: String, case-insensitive
  - NOT_IN: List<String>/List<UUID>
  - IS_NULL / IS_NOT_NULL: usando a abordagem A (Boolean true ativa)
- Reutilizar padrão de teste dos PredicateBuilders já existentes.

Documentação
- Atualizar `docs/guides/FILTROS-E-PAGINACAO.md` com tabela de operações, tipos aceitos e exemplos.
- Ajustar `README.md` (seção de filtros) para citar as novas operações.

Critérios de Aceite
- Novos valores disponíveis no enum e acessíveis via `@Filterable(operation = ...)`.
- Builders aplicados corretamente e cobertos por testes.
- Documentação atualizada e exemplos funcionando.
- Nenhuma regressão nas operações já suportadas.

Riscos e Mitigação
- Alterações de comportamento esperadas são aditivas; não substituem as atuais.
- Testes de regressão nos builders existentes e caminho de ordenação por `relation` permanecem verdes.

Notas
- Para datas, recomenda-se manter uso de `BETWEEN` para intervalos (`LocalDate` já é tratado para `Instant` no builder atual de BETWEEN). As novas operações GTE/LTE aplicam-se a tipos `Comparable` conforme os já existentes.

