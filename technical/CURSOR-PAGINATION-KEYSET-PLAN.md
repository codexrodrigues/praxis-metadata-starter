# Cursor Pagination / Keyset Plan

## Contexto

O `praxis-metadata-starter` hoje publica o endpoint `POST /filter/cursor` no
`AbstractCrudController`, mas a implementação padrão do service ainda não
materializa keyset pagination.

Na prática:

- o controller expõe a rota e delega para `BaseCrudService#filterByCursorMapped(...)`
- o `BaseCrudService` ainda fornece um default que lança
  `UnsupportedOperationException("Cursor pagination not implemented")`
- o `AbstractCrudController` converte esse caso em `501 Not Implemented`

Isso explica por que apps consumidores, como o `praxis-api-quickstart`,
respondem `501` no Render mesmo quando os demais filtros funcionam.

## Direção de Plataforma

A solução correta de plataforma é implementar keyset pagination canônica no
`praxis-metadata-starter`, e não deixar cada app consumidor reinventar a
estratégia por recurso.

O starter já centraliza:

- contrato de filtro
- normalização de payload
- paginação tradicional
- `locate`
- `options/filter`

`/filter/cursor` deve seguir a mesma lógica: contrato e infraestrutura
centralizados, com capacidade explícita e governada.

## O Que o Spring Já Oferece

O ecossistema Spring já fornece primitives importantes para uma implementação
sólida:

### Spring Data Commons

- `ScrollPosition`
- `KeysetScrollPosition`
- `Window<T>`
- `WindowIterator`

Essas abstrações são a base mais adequada para scroll/keyset no nível do
starter. O contrato público do Praxis pode continuar usando `CursorPage`, mas a
implementação interna deve se apoiar nessas primitives.

### JpaSpecificationExecutor + Scroll

Na stack atual do starter, o primeiro caminho a testar deve ser o que o Spring
Data JPA já expõe via `JpaSpecificationExecutor#findBy(..., q -> q.scroll(...))`
com `Specification`.

Isso reduz a chance de criar infraestrutura redundante no v1.

### Repository Fragments

Fragments customizados continuam sendo uma opção válida, mas devem entrar só se
a prova de conceito com `JpaSpecificationExecutor` mostrar limitação real para
o caso canônico do v1.

### Spring Boot AutoConfiguration

O starter já usa auto-configuração para outros recursos. Cursor pagination deve
seguir o mesmo padrão, com propriedades de governança e beans internos
configuráveis.

## Escopo Seguro do V1

O sucesso da implementação depende de restringir o v1 ao caso canônico da
plataforma.

### Requisitos do v1

- entidades com ID simples
- sort estável
- campos de ordenação escalares
- campos de ordenação acessíveis sem sort arbitrário por coleção
- cursor opaco transportado por `after` e `before`
- anexar o ID como tie-breaker sempre

### Restrições deliberadas

Não tentar no v1:

- qualquer sort arbitrário enviado pela UI sem governança
- joins complexos como chave de cursor
- campos calculados como chave de ordenação
- coleções/nested sorts
- contrato universal para recursos inelegíveis

## Arquitetura Recomendada

### 1. Manter `CursorPage` como DTO externo

O contrato HTTP do Praxis já usa `CursorPage`. Isso pode ser preservado.

Internamente, o starter deve converter entre:

- `Window<E>` / `ScrollPosition`
- `CursorPage<E>`

### 2. Executar keyset com a menor camada possível

Prioridade do v1:

- usar `JpaSpecificationExecutor#findBy(..., q -> q.sortBy(...).scroll(...))`
- reaproveitar `Specification<E>` já gerada pelo starter
- encapsular a adaptação em `AbstractBaseCrudService`

Se essa via não bastar, aí sim criar algo como:

- `CursorScrollableRepository<E>`
- `CursorScrollableRepositoryImpl<E>`

### 3. Normalizar o sort para um sort estável

O starter deve forçar ordenação determinística:

- usar sort explícito somente quando ele for cursor-safe
- senão usar o default sort do recurso
- sempre anexar o ID ao final como critério de desempate

Exemplo:

```text
ORDER BY publicadoEm DESC, id DESC
```

### 4. Cursor opaco

O Angular não deve conhecer a estrutura interna do cursor.

O starter deve serializar um payload opaco contendo, no mínimo:

- direção
- campos de sort usados
- valores da última linha

Formato sugerido:

- JSON interno
- codificado em base64url

Assinatura/HMAC pode entrar depois, se houver necessidade de blindagem
adicional.

### 5. Capability explícita

Nem todo recurso deve expor suporte real a cursor pagination.

Precisamos de um mecanismo canônico, por exemplo:

- `supportsCursorPagination()`
- ou `CursorSupportMode`

Valores possíveis:

- `AUTO`
- `DISABLED`

No v1, `AUTO` só habilita quando o recurso for compatível com o contrato
seguro.

## Papel de Cada Camada

### Repository Fragment

- constrói a query keyset
- aplica `Specification`
- resolve `after` / `before`
- devolve `Window`

### Service Base

- aplica governança de sort cursor-safe
- adapta `Window<E>` para `CursorPage<E>`
- expõe implementação padrão reutilizável

### Controller Base

- continua aceitando `after`, `before` e `size`
- deixa de depender de override manual do app consumidor
- no v1, pode continuar publicado, mas deve responder de forma governada e
  documentada quando o recurso estiver desabilitado ou inelegível

## Governança de Contrato

O v1 deve formalizar:

- tamanho máximo (`praxis.cursor.max-size`)
- política global de enable/disable
- regra de estabilidade do sort
- regra para tie-break por ID
- recursos/colunas cursor-safe

Exemplos de propriedades:

```properties
praxis.cursor.enabled=true
praxis.cursor.max-size=100
praxis.cursor.default-mode=auto
```

## Estratégia de Implementação

### Fase 1

- implementar governança de sort + codec + capability
- provar a execução keyset com `JpaSpecificationExecutor`
- implementar adaptação `Window` -> `CursorPage`
- suportar apenas sort canônico + ID
- habilitar em recursos simples do quickstart

### Fase 2

- validar no `praxis-api-quickstart`
- validar no Render
- ampliar para mais recursos elegíveis

### Fase 3

- reavaliar se vale esconder/despublicar `/filter/cursor` para recursos
  inelegíveis depois que a capability estiver madura
- ampliar governança de sort
- considerar assinatura do cursor e observabilidade

## Testes Necessários

### Starter

- codificação/decodificação do cursor
- normalização de sort estável
- `after`
- `before`
- empate por ID
- asc/desc
- filtros combinados com `Specification`
- comportamento com campos nulos
- recurso inelegível retornando `501`

### Consumidor

- teste real sem mock de service
- `/filter/cursor` com `after`
- `/filter/cursor` com `before`
- comparação entre page tradicional e cursor em recursos elegíveis

## Recomendação Final

Sim, implementar keyset canônico no `praxis-metadata-starter` é uma estratégia
sólida, desde que o rollout seja deliberadamente restrito.

A taxa de sucesso é alta se o starter:

- começar pelo caso canônico
- usar primitives do Spring Data Commons para scroll/keyset
- provar primeiro a via nativa do Spring Data JPA antes de criar camadas extras
- governar explicitamente a elegibilidade por recurso

O erro seria tentar resolver no v1 "qualquer sort, qualquer join, qualquer
projection". O caminho robusto é um v1 pequeno, determinístico e
plataformizado.
