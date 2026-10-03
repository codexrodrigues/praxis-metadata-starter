# Candidato privado: execução ATOMIC de conjunto

Estado: implementação e validação em andamento. Esta nota não anuncia publicação,
composição operacional, READY, adoção HTTP ou garantia B4–B7.

## Fonte e aderência

O descriptor e a intenção já carregam `atomicity`; o kernel durável publicado só
executava `PER_ITEM`. A classificação é `suportado-parcialmente` na estrutura e
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

## Gates ainda abertos

O candidato requer compilação, migração e testes PostgreSQL reais, inclusive
1/50/51 alvos nos três modos `DOMAIN_COMMAND`, `UNIFORM_UPDATE` e
`PER_ITEM_UPDATE`, rollback após efeito tardio e admissão dirty, commit
incerto, ACK, recuperação, leitores, ACL e catálogo. A prova de um consumidor
de domínio real e dos receipts/outbox na mesma conexão é obrigatória antes
de qualquer integração ou publicação. Não foi habilitado composer `ATOMIC`,
capability, endpoint ou adoção host.

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
