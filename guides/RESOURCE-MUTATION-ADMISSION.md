# Admissão contextual de mutações

`AbstractBaseResourceService` fornece `validateCreate`, `validateUpdate` e
`validateDelete` para decisões de domínio que precisam conhecer a origem da
mutação. O `ResourceMutationContext` é produzido pelo lifecycle do starter.
Não o desserialize de headers ou payloads para selecionar políticas.

Os hooks `beforeCreate`/`beforeUpdate` continuam preparando e hidratando o
candidato e conferindo referências e escopo. A admissão contextual ocorre
depois dessa preparação e antes de persistir. `validateUpdate` recebe o estado
atual e o DTO; o domínio deve construir NEW e OLD independentes, aplicar ao
rascunho a mesma hidratação/mapeamento da escrita e avaliar suas regras uma
única vez por admissão. Hooks de avaliação não podem escrever nem alterar a
entidade gerenciada. O motor de regras governa as decisões de operações que
possuem RuleSet; guardas estruturais não devem repetir esses predicados.

| Origem | Contexto |
| --- | --- |
| POST | CREATE / DIRECT |
| PUT versionado | UPDATE / DIRECT, após lock e If-Match |
| DELETE de item | DELETE / DIRECT / collection=false |
| DELETE em lote | DELETE / DIRECT / collection=true, por alvo |
| Avaliação de lote de campos | UPDATE / EVALUATION / modo real |
| Revalidação e escrita de confirmação | UPDATE / CONFIRMATION / modo real |

A exclusão em lote adquire os locks na ordem canônica e admite **todos** os
alvos antes da primeira remoção ou de `afterDelete`. Uma rejeição reverte a
transação. Isso não substitui FKs, locks de dependências ou efeitos de domínio.
Essa ordem também se aplica a todos os `beforeDelete`: consumidores que
dependiam de intercalar a preparação do próximo alvo com o efeito do anterior
devem reorganizar o comportamento em guardas anteriores à escrita e efeitos
posteriores, usando os hooks de coleção quando houver uma decisão conjunta.
O contexto da coleção captura um único instante após a preparação do lote e
o compartilha entre todos os alvos, inclusive se a validação atravessar a
mudança de data civil no fuso de negócio.

## Lotes de campos

`BulkDomainResourceAdapter.evaluate` e `update` recebem o contexto explícito.
`UNIFORM_UPDATE` e `PER_ITEM_UPDATE` conservam modo e etapa da intenção
congelada. Não inferir esses dados da presença de OLD. `DOMAIN_COMMAND` mantém
sua porta tipada própria e não é admitido como lote de campos por esse contexto.

A avaliação usa o instante de criação da proposta. Cada chamada de confirmação
ou avanço captura um instante fresco único, compartilhado pela revalidação e
pela escrita dos alvos naquela chamada. O domínio deriva sua data civil desse
instante e de um fuso explícito. Uma nova chamada/retomada pode ter outro instante;
não existe promessa de relógio fixo por toda a vida de uma execução assíncrona.

`validateUpdate` pode retornar um `JsonNode` de evidência protegida da decisão,
como digests do conjunto de políticas e dos fatos dependentes. O adapter guarda
esse resultado junto da fotografia do recurso em `Candidate.facts`; a confirmação
reavalia e compara essa evidência no protocolo nativo antes de escrever. Sem
evidência adicional, preserva-se a fotografia ordinária do recurso. Nunca incluir
segredos ou projetar esses fatos diretamente na resposta pública. O domínio
continua responsável pela completude das populações, autorização e locks que
impedem mudanças concorrentes relevantes entre a avaliação bloqueada e a escrita.

## Migração dos consumidores

Este ajuste Java é incompatível na fonte: implementações/decoradores do adapter
devem aceitar e encaminhar o quarto argumento de `update` e o contexto no lugar
do modo em `evaluate`. Atualizar starter, decoradores e testes no mesmo corte.
Não criar overload antigo, alias ou seleção de política por estado global.
O contrato HTTP de lote e o payload do cliente permanecem os canônicos.

`ResourceMutationContextTest` e `ResourceMutationBulkContextTest` cobrem ordem,
combinações inválidas, os dois modos/etapas, versão e evidência. A aceitação de
um host requer também provas transacionais das regras, FKs, efeitos, rollback,
HTTP/idempotência e do seu modelo de concorrência.
