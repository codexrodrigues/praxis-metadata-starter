# Núcleo publicado: execução ATOMIC de conjunto

Estado em 03/10/2026: núcleo Java e V16 publicados no Metadata `8.0.0-rc.150`,
tag `v8.0.0-rc.150`, apontando para o commit
`986d6f3f8268bd6c3d85aa4b3e87036050af05a5`. O workflow oficial `37135766711`
concluiu, e POM/JAR foram conferidos no Maven Central. A adoção sem override no host
está em validação; publicação/adoção da composição operacional ATOMIC e as
rotas HTTP ATOMIC do host continuam pendentes. Esta nota não anuncia READY, adoção HTTP ATOMIC ou garantia B4–B7.

## Fonte e aderência

O descriptor e a intenção já carregam `atomicity`; até a rc.149 o kernel durável
publicado só executava `PER_ITEM`. A classificação é `suportado-parcialmente` na estrutura e
`lacuna-real-de-contrato` no ledger/executor. A fonte canônica da execução é
`praxis-metadata-starter`; o host fornece admissão e mutação de domínio em uma
transação operacional compartilhada. Consumidores futuros são composer, host
operacional, documentação/skills e fixtures HTTP. O contrato Java beta novo é
restrito ao callback protegido `executeAtomic`, não uma rota pública.

## V16 e fronteira de commit

V16 suspende publicações e controles READY, avança suas gerações e adiciona
vínculos imutáveis de `atomicity` e `protocol_version` à proposta e execução.
O valor da proposta é exatamente o campo do payload protegido; execuções
históricas precisam ser `PER_ITEM`. Novos inserts exigem protocolo 2, sem
default que admita escritor antigo. Uma proposta histórica `ATOMIC` de
protocolo 1 pode ser lida, mas não inicia nova mutação pelo executor novo.

Uma tentativa `ATOMIC` contém 1–50 unidades ordenadas e um digest SHA-256
com framing de comprimentos para fingerprint da avaliação e os digests dos
alvos. O marcador de tentativa usa `active_set_digest`; ordinal/digest de
alvo da tentativa ficam nulos. O prazo agregado da unidade é no máximo 5 s.
A gravação dos filhos reamostra o saldo monotônico e o prazo PostgreSQL; a
transição para `UNIT_COMMITTED_PENDING_ACK` também exige prazo válido no SQL e
no trigger. Uma falha de `statement_timeout` mantém resultado incerto até
readback e rollback comprovado; o instante físico do commit não é atestado
como relógio independente.
A callback de admissão avalia todo o conjunto antes da primeira callback de
mutação. Admissão, mutação, header, todos os filhos e referências declaradas
de efeitos usam a mesma transação operacional. Uma negativa ou falha tardia
aborta integralmente essa transação; só depois do rollback certificado outra
transação, sob fences, registra a rejeição técnica. Cada referência estável
é declarada pelo host e vinculada a um filho; o kernel não inspeciona SQL
arbitrário nem garante exatamente uma vez para efeitos externos.
O header liga `effect_count` e `effect_digest` a todas as referências
declaradas, ordenadas por ordinal e referência e codificadas com framing de
comprimentos. O digest usa SHA-256, prefixo de versão
`praxis.bulk.atomic-effects/1`, contagem decimal, ordinal decimal e cada
referência UTF-8, com tamanho em quatro bytes antes de cada segmento; a
ordem é ordinal crescente e bytes UTF-8 sem sinal (`COLLATE "C"` no SQL).
Cada referência tem 1–200 codepoints UTF-8 válidos. Java e V16 recusam C0/C1
(U+0000–001F e U+007F–009F; PostgreSQL já não armazena U+0000) e os mesmos
16 espaços de borda: U+0020, U+1680, U+2000–2006, U+2008–200A, U+2028,
U+2029, U+205F e U+3000. Espaços internos e caracteres suplementares válidos
permanecem aceitos; a validação não depende de classe POSIX nem locale.
O Java rejeita comprimento bruto acima de 400 unidades UTF-16 antes de varrer
codepoints e valida as até 400 referências antes de ordenar ou codificar bytes.
O SQL recompõe o digest a partir das linhas também no gate de retenção.
Inserts de filhos/efeitos exigem o mesmo attempt ativo sob lock
da execução; a transição PENDING só ocorre após conferência do conjunto,
quantidade e digest, e nenhum insert adicional é aceito depois dela.

Commit incerto nunca invoca novamente callbacks. O header único é consultado
antes de gates de nova mutação; sem prova de receipt e sem rollback certificado
o estado permanece `RECONCILIATION_REQUIRED`. Recovery apenas reconcilia sob
epoch novo e não executa domínio. Um header completo fica invisível na leitura
até ACK ou recovery certificado; o watermark então avança de 0 a N de uma vez.
Um STOPPED certificado pode projetar N `NOT_PROCESSED` quando não há header nem
filhos. `UNIT_ROLLED_BACK` exige rejeição tipada ligada ao digest; uma parada
pré-tentativa por prazo, retirada ou cancelamento usa ausência fenced, sem
inventar uma rejeição de alvo.

As transições terminais previstas têm evidência distinta:

| Origem | Estado final | Evidência necessária |
| --- | --- | --- |
| ACK/recovery de commit certificado | `COMPLETED`, watermark N | Header, N filhos ordenados e digest/contagem de efeitos iguais aos refs. |
| Negativa de admissão ou falha tardia com rollback certificado | `STOPPED`, `UNIT_ROLLED_BACK`, watermark 0 | Header/filhos ausentes e rejeição tipada única ligada à tentativa e ao conjunto. |
| Prazo após tentativa, com rollback certificado | `STOPPED`, `DEADLINE_EXCEEDED`, watermark 0 | Header/filhos ausentes; se houver rejeição, ela é `DEADLINE_EXCEEDED`. |
| Prazo antes da tentativa ou cancelamento antes de domínio | `STOPPED`, watermark 0 | Ausência fenced de header/filhos/rejeição, sem callback de mutação. |
| Recuperação de tentativa sem commit certificado | `STOPPED`, `RECOVERY_STOPPED` ou `CANCELLED_BY_USER`, watermark 0 | Epoch novo, header/filhos/rejeição ausentes; nenhuma callback nova. |
| Resultado de commit inconclusivo | `RECONCILIATION_REQUIRED`, watermark 0 | Não inventar terminal nem inferir ausência a partir de timeout. |

O reader verifica essa evidência antes de retornar até uma página vazia. A
função SQL de evidência terminal é também o gate da retenção privilegiada.

O protocolo 1 `PER_ITEM` mantém seus receipts/replay históricos. Retention
remove header, filhos, referências e rejeição somente após verificar evidência
terminal e continua emitindo tombstone protegido. O bootstrap de ACL V16
concede às roles runtime explícitas somente SELECT/INSERT nas quatro tabelas
novas; a role de retenção tem SELECT/DELETE. Grants são feitos apenas no
marcador PENDING e nunca reparados silenciosamente após COMPLETE.
`migrate` e `validate` atestam os mesmos grants e o marcador COMPLETE; nenhum
dos dois repara ACL alterada depois da conclusão.

## Provas aprovadas e gates ainda abertos

O núcleo foi integrado pelo PR223; a correção ERROR-WIRE pelo PR224. Campanhas
focais aprovadas em PostgreSQL: núcleo 101, regressão Durable 84/Reader 10 e
Proposal 16 após correção test-only. A fixture histórica rc.149 passou também
com POM público; package/Javadoc não substituem essas provas. Elas cobrem o
kernel/ledger, incluindo 1/50/51 nos três modos, rollback tardio, admissão dirty,
ACK, reconciliação, leitura, ACL e catálogo. Não certificam HTTP de domínio.

No checkpoint histórico, o host implementou um consumidor privado package-private
de participantes de missão, validado com SDK DEV identificado da fonte Metadata
integrada, mantendo então o pin público rc.149. Essa evidência não substitui a
validação atual da adoção pública rc.150. A evidência composta tem 56 testes distintos verdes:
18 ATOMIC PostgreSQL, provider 12 PostgreSQL/8 unitários e executor 18 PostgreSQL.
Admissão coletiva e mutação usam a mesma conexão operacional/JPA com domínio,
receipt e filhos; provas incluem rollback SQL na última escrita, replay sem
callback após mudança de grant/policy, versões/fatos, schema, modo cruzado,
plano protegido inconsistente e recuperação de reserva RUNNING com epoch novo
e controle antigo FENCED. Esta última não certifica recuperação UNIT_IN_FLIGHT
ou commit incerto. A fixture usa binding MVC compilado e OpenAPI resolver simulado:
é prova de consumo do kernel, sem produtor/lifecycle/HTTP. Não há outbox no domínio
desse piloto; referências de efeitos não foram inventadas.

O gate de preparação/publicação rc.150 concluiu os passos de `RELEASING.md`,
incluindo contrato acumulado contra a tag149, clean verify e Javadoc. A
disponibilidade Central também foi confirmada. O gate atual é adotar exatamente
o artefato público no host sem override; não integrar um consumidor que dependa
de APIs ausentes no pin. Composição, capability, lifecycle/producer/HTTP ATOMIC,
outbox quando aplicável, purge maduro e demais B4–B7 continuam gates próprios.
A publicação do núcleo protegido não é liberação automática de endpoint ou READY.

## Fixture histórica de publicação

O teste de cutover usa o JAR publicado rc.149, obtido no Maven Central por URL
HTTPS fixa e SHA-256 fixo no build. A obtenção não passa pelo resolver do reactor:
quando o próprio projeto está em rc.149, `dependency:copy` resolveria o projeto
atual ainda não empacotado, em vez do artefato histórico. O download permanece
fora do classpath comum e dos recursos empacotados. Arquivos já presentes e o
cache também são verificados; bytes adulterados não podem substituir a fixture.

O helper verifica novamente o SHA-256, a origem das classes no JAR isolado e a
ausência de V16 antes de usar somente migrate/validate públicos históricos. O
loader/TCCL é restaurado e fechado. Esse download usa diretamente o Central,
independentemente de espelhos Maven: exige acesso HTTPS ou cache íntegro; não
faz instalação local da coordenada pública nem substitui prova de adoção do host.

## Correções antes da primeira publicação V16

A campanha de preparação revelou que operadores PostgreSQL `json` também
materializam escapes de strings alheias ao campo consultado. Apenas trocar `jsonb`
por `json` não evita a rejeição de NUL escapado permitido no snapshot canônico.
V16 extrai `atomicity` de uma cópia lexical que substitui esse escape somente para
parsing; payload e fingerprint persistidos permanecem byte a byte intactos. O
CHECK compara com `IS NOT DISTINCT FROM`, impedindo que ausência/null passem por
SQL UNKNOWN. O migrador atesta a expressão exata; o catálogo anterior é recusado.
Isso preserva a linguagem validada pelo codec, sem afirmar suporte a JSON arbitrário.

`BulkAtomicProposalJsonConstraintPostgresTest` prova NUL em chaves/valores,
sequência literal de barras, bytes intactos, metadata ausente/nula/tipo inválido/
divergente, JSON/UTF-8 inválidos e drift de catálogo. `BulkEvaluationStorePostgresTest`
preserva os casos reais de upgrade/backfill histórico. Fixtures de inserção usam o
tuple READY/publicação existente; a prova de catálogo usa banco vazio e não remove
proposals protegidas nem desabilita guards de admissão/delete.

O envelope ERROR-WIRE correlato passa a usar achatamento explícito no DTO,
independente do mixin do mapper. Plain ObjectMapper e Spring/MVC devem preservar
campos tipados únicos, extensões flat e rejeição do wrapper `properties` de entrada.
Essas correções não habilitam composer/HTTP ATOMIC ou substituem a disponibilidade
pública do artefato e a adoção sem override pelo host.


## Planejamento do incremento de composição B4 (ainda não publicado)

Baseline Metadata main `7206c53009e3a4ef9921d3e3df8a0282108ee086`. Esta seção preserva o inventário histórico anterior ao patch, revisado por B003; não altera o estado publicado rc.150. No candidato atual, a composição já foi implementada e passou em 83 testes focais. Consumidor Maven, revisão final, skills, integração/publicação e HTTP ATOMIC do host são gates separados.


**Classificação:** arquitetural/contrato-publico; desenho anterior ao patch. Adoção rc.150 e esta decisão são gates distintos. A decisão não publica capability, endpoint ou READY; a implementação requer revisão e provas próprias.

**Inventário de aderência.** `atomicity` já pertence ao descriptor, binding, fingerprint estrutural e `BulkExecutionContract`; as anotações e o compiler já distinguem operações globais de avaliação/confirmação. O kernel ATOMIC e V16 estão publicados em rc.150. Na baseline anterior ao patch, `BulkOperationalDescriptorComposer` e `BulkExecutionContract` restringiam composição operacional a PER_ITEM; `CapabilityOperation`, a projeção de updates e a cardinalidade em `BulkOperationLifecycle` distinguiam apenas mode. A classificação histórica `suportado-parcialmente` identificava a falta de materialização e invariantes para coexistência, não um segundo modelo de operação. O consumidor privado do host não substitui provider/mapping/composição/HTTP reais.

**Decisão canônica.** A identidade local CRUD é derivada por regra fechada de `(mode, atomicity)`: UNIFORM_UPDATE/PER_ITEM → `bulk-update`; PER_ITEM_UPDATE/PER_ITEM → `bulk-update-items`; UNIFORM_UPDATE/ATOMIC → `bulk-update-atomic`; PER_ITEM_UPDATE/ATOMIC → `bulk-update-items-atomic`. Preservar as duas identidades PER_ITEM e seu wire. Uma única declaração por `(resourceKey, mode, atomicity)`; repetição é erro, nunca escolha silenciosa. A regra vive em um único dono canônico Metadata e é reutilizada por validação/projeção, sem campo novo em annotation, enum artificial de todas as capabilities, registry paralelo ou mapas aninhados de variantes. DOMAIN_COMMAND continua action de domínio com identidade própria; esta regra não o converte em CRUD.

A autoridade durável continua `(namespace, confirmationOperationId)`; não usar capability local como chave do ledger ou substituir operationId por alias. Provider e operações HTTP globais explícitos são exclusivos de cada variante. Os cinco handlers de leitura/cancelamento continuam compartilhados por resource; avaliação e confirmação ATOMIC são operações próprias, com referências e schemas reais distintos das PER_ITEM. Não renomear/reinterpretar rotas PER_ITEM, nem permitir que request/UI troquem atomicidade. Paths novos devem ser normatizados em ApiPaths no pacote host antes de criar mappings, não derivados no cliente.

**Limites e invariantes.** Composição ATOMIC inicial somente EXPLICIT/SYNC, conjunto inteiro 1–50 e orçamento agregado de 5s. Perfil genérico mantém teto PER_ITEM200; verificar os limites ao combinar descriptor e perfil, sem copiar atomicity para outro contrato. Ausência/duplicação/troca de provider, divergência de confirmationOperationId, mapping/schema/OpenAPI incompatível ou limites inválidos fecham composição. Provider, identidade e registro de controle/generation/fingerprint são exatos por variante. A fotografia de publicação e sua fence são globais: suspender uma identidade invalida a fotografia e fecha todas as variantes; nenhuma outra ganha autoridade por essa suspensão. Republicar uma identidade recaptura e valida a fotografia global, mas somente essa variante volta a READY e à projeção de capabilities; as demais permanecem indisponíveis até sua própria publicação. Não confundir a fence global com autorização ou prontidão coletiva. O protocolo mantém estados duráveis e ordem de locks de domínio P3 já aprovada G→E→M→P, subordinada à ordem operacional de binding/publicação/control/execution/evidência; esta decisão de discovery não altera transação, fences, receipts ou recuperação e não autoriza novas mutações durante recuperação.

**Mapa de impacto.** Fonte: Metadata composer, BulkExecutionContract, CapabilityOperation e BulkOperationLifecycle, compiler e respectivos testes. Consumidor concreto: MissaoParticipanteController/provider do quickstart, duas modalidades de atualização e cinco handlers compartilhados; integração ATOMIC futura reutiliza consumer privado após composição real. Derivados: docs canônicas de capabilities/ATOMIC, runbooks host e skills concurrency/discovery. Corpus/receita pública só após prova HTTP; Angular/landing permanecem depois B7. Risco público: novas identidades de capability e cardinalidade; PER_ITEM deve permanecer byte a byte compatível. Não há mudança no formato do request de confirmação, ledger ou token.

**Provas de aceite antes da publicação.** Coexistência PER_ITEM/ATOMIC para mesmo resource e mode; rejeição de duplicatas do mesmo trio; controle exato por variante, suspensão invalidando a fotografia global e republicação reabrindo somente a identidade publicada, com as demais ausentes até publicação própria; provider ausente/trocado/duplicado não executável; limite ATOMIC1/50 aceito e51 rejeitado antes de gravar proposta/mutação, PER_ITEM200 preservado; refs/schema/fingerprint consistentes no mesmo documento OpenAPI e cinco handlers sem body. No host real: avaliar/confirmar com provider ATOMIC, provar domínio e receipt conjunto na mesma transação, rollback integral, replay sem domínio, autorização/precondições e leitura/cancelamento existentes, em PostgreSQL e HTTP. Prova privada existente é complementar; não declarar T14/T15 ou B4–B7 concluídos por este desenho.

**Ordem registrada antes do patch (estado atual no checkpoint acima):** adoção pública rc.150/verify/PR364 e implementação canônica podem avançar em paralelo apenas com isolamento comprovado: host congelado usa JAR Central imutável, novo checkout Metadata tem fonte/target próprios, sem instalar coordenada pública, publicar ou alterar a campanha host. Antes da escrita registrar baseline/recursos e revisar esta decisão; implementar materialização e testes canônicos delimitados; revisão independente; consumer real host e provas HTTP/PG; docs/skills e integração com SHAs exatos. Nenhuma release adicional é autorizada apenas pela aprovação do desenho.


## Composição posterior de comandos ATOMIC — candidato separado

A composição CRUD acima foi publicada no Metadata rc.151; sua adoção HTTP no host
continua gate independente. O próximo candidato elimina apenas os vetos de
DOMAIN_COMMAND/ATOMIC no composer e na projeção, mantendo ação, provider, identidade,
parâmetros tipados, sete referências e limites canônicos. WorkflowAction e declaração
bulk devem concordar na atomicidade. Não projeta comando como capability CRUD nem
altera o kernel/ledger. O orçamento de cinco segundos é agregado para o único conjunto
ATOMIC. Provas, consumidores e skills deste novo candidato permanecem gates próprios.
Ver [inventário, impacto e aceite](BULK-ATOMIC-DOMAIN-COMMAND-CANDIDATE.md). Não fecha
CREATE/requestReference, versão de ausência, IAM/provider RuleLab ou outbox T15.
