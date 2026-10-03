# Operações bulk CRUD — composição operacional e candidato B4

O vínculo estrutural entre PUT e DTO de update está em
[BULK-CRUD-STRUCTURE.md](BULK-CRUD-STRUCTURE.md). Este corte candidato compõe a
projeção de descoberta síncrona. O SDK publicado rc.150 compõe PER_ITEM; o incremento B4 desta fonte candidata acrescenta composição ATOMIC para CRUD, ainda sem artefato público novo ou adoção ATOMIC do host. Não transforma
um descriptor estrutural em autorização nem certifica mutação do domínio no host.

## Descoberta coerente

A identidade de uma operação UPDATE é derivada uma única vez de `(mode, atomicity)`: UNIFORM_UPDATE/PER_ITEM → `bulk-update`; PER_ITEM_UPDATE/PER_ITEM → `bulk-update-items`; UNIFORM_UPDATE/ATOMIC → `bulk-update-atomic`; PER_ITEM_UPDATE/ATOMIC → `bulk-update-items-atomic`. As duas identidades PER_ITEM permanecem iguais. Rejeitar duplicata de `(resourceKey, mode, atomicity)`, sem escolher uma declaração silenciosamente. A operação projeta essa identidade em `capabilities.operations`, com escopo `COLLECTION`, método
`POST`, caminho `.bulk` e referências canônicas às sete operações do ciclo.
O frame de resposta é comum a action e capability: a mesma geração, fingerprint
e revisão estrutural governam o resultado publicado e `requireReady`. A
projeção inclui `editableFields.sourceOperation` (`CanonicalOperationRef`),
`requestSchema`, `writableFields` e `clearableFields` ordenados, derivados do
DTO de update real. Disponibilidade contextual é calculada fora dos locks de
preparação/cache e não substitui autenticação ou autorização da execução.
`collectionOperationAvailability` continua sendo decisão contextual do provider,
inclusive para IDs de operação desconhecidos; ela não prova suporte estrutural
nem READY. A projeção READY deste corte é a capability no mesmo snapshot/frame,
com caminho `.bulk` e sete `CanonicalOperationRef`. As bases não acrescentam
`_links` bulk automaticamente aqui. Um futuro link bulk terá de derivar da
operação desse mesmo snapshot e cerca, sem lookup paralelo ou URL por convenção.

Uma declaração UPDATE válida sem provider fica sem READY; não se anuncia
capability executável a partir do PUT ou da allowlist isolados. Se coexistir um
command operacional, uma declaração UPDATE estrutural válida sem provider não
deve degradar o READY desse command. Declaração órfã, fonte/schema inválidos ou
binding malformado falham na composição: o lifecycle compila todas as
declarações antes de filtrar modos operacionais e não promete isolamento de
erro por operação. Os gates de identidade de `publish` e `requireReady`
rejeitam órfãos antes de I/O durável; a preparação de resposta pode capturar
evidência durável antes de compilar. Retirar o provider faz `requireReady`
negar, mas não muda automaticamente a linha READY durável. `suspend` ou
invalidação seguem o próprio protocolo para fechá-la; um snapshot antigo não
autoriza execução. Provider, confirmação e controle durável são exatos por variante; a fotografia de publicação e sua fence são globais. Suspender uma identidade invalida a fotografia e fecha todas. Republicar uma identidade recaptura/valida o documento global e reabre somente essa variante; as demais ficam ausentes até publicação própria. Os cinco handlers bodyless de leitura/cancelamento são compartilhados, enquanto avaliação/confirmação pertencem à variante.

## Perfil e limites

O perfil UPDATE é singular por operação, com execução síncrona e seleção explícita (`SYNC`, `EXPLICIT`). PER_ITEM mantém teto de 200 alvos. O candidato ATOMIC aceita 1–50 alvos e prazo agregado da unidade de até 5 segundos; perfil fora desses limites não compõe. Atomicidade pertence à operação, nunca ao request/UI. DOMAIN_COMMAND/ATOMIC permanece fechado neste composer. Não acrescenta consulta de seleção ou execução assíncrona; a composição não prova mutação, receipt ou recuperação do domínio do host.
`DOMAIN_COMMAND` conserva os bytes do digest `praxis.bulk.structure/3`;
UPDATE usa `praxis.bulk.structure/4` com fonte, schema, allowlists e campos
protegidos. `parametersPointer` é exclusivo do command e fica ausente em
UPDATE. O provider continua fornecendo `identitySchemaPointer`: para seleção
explícita, `/properties/selection/properties/targets/items/properties/id`; para
itens, `/properties/items/items/properties/id`. Os gates de identidade de
`publish` e `requireReady` negam órfãos antes de I/O durável; nenhuma inferência por nome de operação ou
capability pode preencher uma declaração ausente.

O frame mantém a cerca de snapshot e a verificação final mesmo quando uma
regra do host captura uma negativa; após falha da verificação final, a resposta
é envenenada e não pode ser convertida em disponibilidade. O escopo e a cerca
devem ser descartados em `finally`, sem reter lock de cache nem transação JDBC
ao chamar regras do host ou montar a resposta. Esta composição não cria grants
P3, autorização para mutação, rotina de domínio, release ou adoção Angular.

## Evidência histórica P3b-S2 e limites

Na fonte candidata congelada, as campanhas `focal-01` (75 passes reutilizados),
`focal-02` (8), `http-04` (1 TCP) e `regressions-01` (12) aprovaram **95 casos
Metadata distintos após deduplicação**. As falhas iniciais de fixture foram
diagnosticadas e corrigidas sem retirar os gates; não houve falha no resultado
focal final. O oráculo do command `praxis.bulk.operational/1` permaneceu
preservado. A prova inclui PostgreSQL 14.22 para controle e HTTP TCP real para
discovery, frame, controle e referências; não certifica mutação de domínio nem
receipt CRUD executado. Dois focais existentes do kernel de replay
`receipt-first` passaram, sem ampliar esta afirmação à execução CRUD.

A revisão independente aceitou o corte focal. Dois testes candidatos do host
também passaram contra o artefato local, sem mudança de fonte durante a prova;
os registros estão em `/tmp/praxis-p3b-operational-proof-20261001`. A integração da fonte é distinta da publicação e adoção do binário; não houve
suíte total, release, adoção pública ou validação Angular. O perfil operacional UPDATE focal não equivale a backend
completo ou READY para todo consumidor.


## Evidência do candidato B4 — 03/10/2026

Fonte congelada: 83 testes distintos em seis classes, zero falhas/erros/skips: composição/estrutura/bindings (36), lifecycle PostgreSQL/capabilities HTTP/compiler (47). Os oráculos incluem limites ATOMIC aceitos 1 e 50 nos dois modos com 5s exatos, rejeição 51, PER_ITEM200 preservado, quatro variantes no mesmo recurso/fotografia, provider exato, colisão e suspensão/republicação. Relatórios e freeze estão em `/tmp/praxis-b4-atomic-candidates-20261003/b4-composition-*`. A prova HTTP pertence ao SDK de discovery; não certifica execução ATOMIC do host, publicação do novo artefato, outbox, B4–B7 ou Angular. Revisão final, consumidor Maven isolado e skills são gates separados.
