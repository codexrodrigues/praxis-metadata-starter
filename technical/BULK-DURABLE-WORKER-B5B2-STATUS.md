## Estado atual — candidato privado V20 revisado (08/10/2026)

Fonte e provas qualificadas aceitas por ROOT: V7two `9116c8ac`, crescimento
`27dd0b8b`, pool4 `f4d64094`, artefato/consumidor V9 `3f526c43` e guidance
`cf9ef507`. SQLV1–19 preservado; V20 índice, bootstrap em etapas e provas de
origem exata/bytes do JAR conferidos. Não há whole verify sintetizado.

Skill maintenance integrada pelo [PR716](https://github.com/codexrodrigues/praxis-codex-skills/pull/716),
main `63cf64211e09c5d61dd2e5109483dd159ca7286f`; sync seletivo concluído.
Audit focal:1OK/0drift; exit1 do manifesto parcial decorre das166 fontes fora da
seleção, não de drift na skill.771 arquivos de outras skills preservados.

Publicação/adopção host continuam rc.155/V19. Este candidato é privado/testado;
integração SDK ainda pendente. HTTP do consumidor é route-dispatch da fixture,
sem prova de mutação de domínio/receipt/job off-request. READY observado é da
fixture/SYNC, não ASYNC202, fleet readiness, B7 ou backend completo.

Próximo gate: staging/diff/revisão de integração SDK com escopo e SHA exatos;
sem release/deploy/host/Angular. Consulte [guia owner/cutover](BULK-DURABLE-WORKER-OWNER-UPGRADE.md).
As seções seguintes são histórico das campanhas e seus estados na execução;
não são gates atuais. Fontes antigas permanecem vinculadas às próprias provas.

Guia privado: [migração owner e cutover](BULK-DURABLE-WORKER-OWNER-UPGRADE.md).

## Histórico — V7 — pool real limitado a quatro conexões PASS (08/10/2026)

Resultado `worker-index-v7-owner-pool4-run1-result.json`, SHA256
`54392133198a71376c6c84efba3e0d80d094c1d288d3847426bd8f5dca7165bf`:
Maven0/1JUnit/0fail/0errors/0skip. Mesmo860fullfreezeV7 sem drift. Método existente
Hikari pool4: instalação fresca20, marcadores read/occupancyCOMPLETE e validate
estrito final; manifest sampling/cleanup preservado em provas da campanha.

ROOT revisa runtime antes do aceite. Pool4 é hipótese positiva, não tamanho
mínimo nem prova de frota, HTTP público ou READY. Session83036 encerrada,
sem Maven/PG próprios mantidos. Próximos riscos: packaged artifact e fechamento
documental/skill canônica manutenção no mesmo ciclo; sem release/host/Angular.

## Histórico — V20 — crescimento aceito; próximo gate focal pool4 (08/10/2026)

ROOT aceitou a medição: `root-worker-queue-index-v7-growth-run1-independent-review.json`
SHA256 `27dd0b8b72096c40edc96cf6cbb9af9df990bcf332f60944b212fdced72b8668`.
128/512: Index Only Scan com Heap Fetches1/1/0; não afirmar heap-free nem
confundir linhas emitidas com todas as entradas visitadas. Buffers de pais são
inclusivos.32:scan34 registros/4 páginas; baseline128/512:scan12/49 páginas;
V20:1–3 páginas no acesso. Seed5.69/16.21/63.57s não é SLO.

Próximo risco concreto: stagedDDL e loans independentes exigem prova com pool
Hikari real. MAIN propõe apenas `freshPublicOwnerMigrationFitsFourPoolConnections`,
sem inferir tamanho mínimo nem repetir negativos/twoowners sem causa nova.
Plano/hashes aguardam gate ROOT, nenhum novo build. Docs e skill manutenção
continuam pendentes no mesmo ciclo; compiled artifact será avaliado na integração.

## Histórico — V20 — crescimento finito medido; revisão independente pendente (08/10/2026)

Resultado `worker-index-v7-growth-run1-result.json`: Maven0,
1JUnit/0fail/0errors/0skip,860 fontes sem drift na mesma árvore V7. Nove planos
reais:32STOPPED+2QUEUED ainda usam Seq Scan+Sort (scan4 páginas cacheadas);
128/512STOPPED+2QUEUED usam Index Only Scan do índice V20 (1–3 páginas cacheadas
no nó de acesso, zero reads). Não somar buffers inclusivos dos nós pai.

Direitos, publicação, slots, allocations, controles, ordinals e ausência de
mutação/receipts foram verificados pelo método existente; sem forçar planner
ou aumentar budgets. Trata-se de PostgreSQL14.22/cache/corpus finito, não SLO,
T13, PG16 ou certificado de carga corporativa. Baseline pré-índice preservado.
ROOT revisa a evidência física antes de escolher pool/artifact. Session28134
encerrada; lease sem Maven/PG próprios. Sem release, host ou Angular.

## Histórico — V7 — retomada histórica: dois focais aceitos independentemente (08/10/2026)

ROOT conferiu relatórios XML frescos, exit Maven0,860 hashes de fontes/arquivo
sem drift e cleanup:2JUnit/0fail/0errors/0skip. Parecer
`root-worker-queue-index-v7-two-run1-independent-review.json`, SHA256
`9116c8ac1af4bc1893b6fd68b9cc9717a2752eff11c2f3ff0d802d4380fb9458`. Dados/history/checksums permanecem preservados; declaração incompleta de
roles é rejeitada antes do bootstrap, retry com roles completas instala V20;
upgrade V7 retido e replay0 comprovados. V5/V6 RED continuam preservados com seus
diagnósticos, não são apagados por este PASS.

O próximo gate mede o método existente de crescimento32/128/512 sob V20, com
budgets e direitos inalterados. Esse resultado ainda não foi executado nesta
fonte. Aceite dos componentes não é whole verify, aceite do incremento completo,
READY público ou fechamento backend. Documentação/skill manutenção permanecem
pendentes no mesmo ciclo antes da integração.

# Worker durável B5b.2 — estado do candidato

Atualizado em 08/10/2026. O worker está implementado em candidato privado; a primeira bateria de 13 testes PostgreSQL passou e recebeu aceite independente delimitado. O backend de operações em lote ainda não está concluído. Este corte não publica ASYNC, HTTP 202, READY, auto-configuração do worker ou uma receita de adoção pelo host.

O [desenho B5b.2](BULK-DURABLE-WORKER-B5B2-DESIGN.md) preserva a proposta histórica anterior à implementação. Suas afirmações sobre ausência de implementação/build descrevem aquele momento. Este documento registra o estado posterior; não substitui evidências nem altera o contrato.

## Histórico — Primeiro focal do índice V20: RED em diagnóstico

A fonte V5 foi revisada antes do primeiro focal (08/10/2026). A compilação
concluiu, mas Maven terminou1:13JUnit,3falhas,1erro,0skips. Seis métodos do
índice passaram;9cenários físicos pertencem a um deles. Sem drift nas860fontes.

As três falhas envolvem oracle/fixtures e mensagem de um gate anterior. O erro
da retomada V7 mostrou um preflight que exige a linha global antes de sua criação
deny-only no bootstrap pendente. ROOT está avaliando correção estreita. Não
há aceite de V20 ou B5b.2; preservar os resultados brutos, corrigir com revisão
independente e repetir somente as provas afetadas. Nenhum processo próprio ativo.

## Histórico — Revisão das fontes V20 em andamento

As fontes V3 corrigem retomada owner de V19 com dados históricos e dispatch
concorrente19/20, seguindo a direção independente ROOT. Testes editoriais
latest/reties foram confrontados com os predecessores históricos; acrescentadas
provas de dados retidos e rejeição de vínculo conflitante. Nenhuma compilação ou
bateria PostgreSQL deste índice foi executada ainda; aceite das fontes e provas
continua pendente. As evidências anteriores do worker não certificam a migration V20.

## Histórico — Responsabilidades

O Metadata conserva a fila PostgreSQL, a ocupação QUEUE/ACTIVE, o controle de owner/epoch, a unidade transacional e os receipts. O loop privado usa esses mecanismos existentes para selecionar candidatos e executar uma unidade por vínculo a cada rodada. Uma indicação de fila ou de slot livre nunca substitui o claim durável.

O host continua responsável pelo vínculo físico entre domínio e infraestrutura, pela autorização atual, pelas regras de negócio e pelos callbacks concretos. Os callbacks das provas são de conformidade; não certificam um adaptador corporativo sem contexto HTTP/JWT. Essa composição confiável e sua auto-configuração suportada pertencem a B5b.3. O Config Starter permanece a fronteira de authoring governado e não assume a fila ou os receipts do Metadata.

A fila deste corte é PostgreSQL; RabbitMQ/Kafka não são pré-requisitos. A composição é privada e explícita: não existe início automático apenas pela presença de um datasource.

## Histórico — Garantias já comprovadas neste candidato

- Duas JVMs disputam o mesmo trabalho pelo claim real; apenas uma chama o domínio e grava o receipt.
- Domínio e receipt confirmam na mesma transação física. Um processo reiniciado observa a execução terminal sem repetir o efeito.
- O quantum atende outro vínculo antes de terminar todos os ordinais do primeiro. Isso é justiça local de atendimento, não certificação de justiça distribuída ou de um SLO.
- Uma execução QUEUED pode expirar sem instalação ACTIVE. Cancelamento anterior à seleção não chama o domínio.
- A recuperação com owner/epoch antigos não altera a execução de um sucessor.
- O stop impede novos ordinais; uma unidade já autorizada pode concluir. O callback do lifecycle aguarda a saída real da thread.
- O fechamento Spring cooperativo foi comprovado com pool real. Também foi comprovado o caso negativo de timeout da fase: o contexto pode fechar o datasource enquanto o worker ainda está pendente. Não há garantia de sobrevivência do datasource após esse timeout.

Timeout SQL não limita, por si só, aquisição de conexão, rede ou um callback Java que não coopera. Esses limites não devem ser apresentados como prazo máximo universal de shutdown.

## Histórico — Proveniência e limite do aceite

Baseline publicado: Metadata `8.0.0-rc.155`, commit `b35483beac248c20bc107dd9e966a7a70a7d67a2`. Coordenada do candidato: `8.0.0-b5b2-durable-worker-20261008-SNAPSHOT`, com cache separado e sem instalação sob a coordenada pública. O host continua na dependência publicada rc.155.

A campanha `worker-first13-run1` reportou 13 testes, zero falhas, zero erros e zero skips, com Java 21. O arquivo de fontes da campanha tem SHA-256 `6745040a3f9fd540d8f643ad5a50d751c41b35a1691f8599f6342944017042ad`; a revisão independente tem SHA-256 `e8a334072c428abcb2a5f919669bb41a766d52efa52f01523d068102d2b85526`. O planejamento do host registra os caminhos das evidências locais, os hashes e o aceite delimitado. Esses arquivos temporários não são artefatos públicos de release.

## Histórico — Garantias adicionais após a correção da varredura

Os quatro focais aceitos na árvore com o retorno imediato do cursor demonstram que a limpeza certificada de uma execução QUEUED expirada continua sob FENCED, sem reabrir o marker, alterar instalações, devolver direitos à autoridade global ou chamar o domínio. Também demonstram que essa limpeza ocorre entre duas unidades de outra execução em andamento no mesmo vínculo, preservando seus efeitos e receipts. A limpeza não é concorrente com um callback bloqueado: acontece depois do ACK e antes de autorizar o ordinal seguinte.

A prova adicional `active-full-wrap-one-run2` foi aceita independentemente (relatório SHA-256 `5f67f80619d49dc69c96e7756f8c18057ef899b553c5f72957bc85bbb19b409f`): o worker observa um slot ACTIVE ocupado e o trabalho QUEUED intacto; a recuperação oficial libera o slot e o loop retorna ao candidato anterior, reutiliza o mesmo slot com sequência2 e confirma dois efeitos/receipts físicos, sem efeito do holder nem devolução de direitos globais. Essa prova usa instrumentação JDBC test-only com pausa única700ms; não é uma prova sem instrumentação nem um teste de carga/SLO.

A expiração sob SUSPENDED também foi comprovada e aceita independentemente em `suspended-expiry-one-run1` (relatório SHA-256 `8a2f4097b5171364bccace8f9b017dcd64497c2938fbf62ba1f480fa467dc7b8`). A suspensão da operação avança sua geração pela transição oficial e permanece intacta após a limpeza da fila: nenhum callback, receipt, mudança de domínio ou devolução de direitos à autoridade global. Não há reativação automática da operação nem execução retomada.

O caso com duas operações distintas na mesma foto foi aceito em `operation-head-two-run1` (relatório SHA-256 `70d92b24d3f1c805a98f5cb7cbec8816bdfeff0f7a95fe990150d0c66edf4e08`). A entrada suspensa tem handler correspondente, mas o claim real é rejeitado pelo PostgreSQL com SQLSTATE55000; o loop avança à operação saudável e ela conclui duas unidades físicas. A entrada permanece QUEUED sem callbacks/receipts, e os controles, a foto e a autoridade permanecem intactos. Isso prova rejeição antes da aquisição do handle saudável; não prova exceção de claim enquanto o handle já está em execução. A foto opaca da fixture certifica armazenamento/controle privado, sem certificar composição SpringDoc ou disponibilidade pública.

A identidade completa do handler foi aceita em `full-roster-identity-one-run1`
(relatório SHA-256 `acb27e6c6d8c9991d54fd26168d961fbdf2874fb54065baa31aaaa28ffb7ffde`).
Um método JUnit percorre cinco rosters incorretos independentes — recurso, grupo,
operationId, path e método — sem claim ou callback, seguido de uma referência
correta que confirma duas unidades físicas. A fila, ocupações, controles e foto
permanecem intactos em cada recusa. Isso não certifica isolamento entre namespaces,
tenants ou grants. No desenho do próximo caso, dois namespaces do mesmo deployment
compartilham a publicação global; o controle B deve consumi-la sem republicar a foto.

O isolamento de namespaces no mesmo banco foi aceito em `namespace-two-run1`
(relatório SHA-256 `6ed6da7c018e940bf6f8f652ecdf4329bc80b7f376041de02bfff1d5977e43c6`).
A e B têm o mesmo recurso e operação completa; B é anterior na fila. O worker A
conclui A e deixa B inteiro intacto. Após o encerramento de A, o worker B usa seu
runtime/TM e confirma as duas unidades físicas. A publicação global, os controles,
as instalações e os direitos permanecem iguais; um slot ACTIVE é reutilizado com
sequência2. Não é prova de tenant, grants atuais ou composição SpringDoc pública.
A collection de certificados da fixture registra apenas A; B é provado por suas
asserções SQL físicas independentes no teste congelado aprovado e report próprio.

## Histórico — Pendências para fechar B5b.2

Restam casos adicionais de ordenação do cursor e o ajuste canônico do caminho de acesso à fila. A avaliação de crescimento foi concluída e aceita como diagnóstico finito. O isolamento de namespace deste cenário foi comprovado. O EXPLAIN com histórico retido recebeu aceite como diagnóstico: 32 terminais e dois QUEUED, orçamento real de SQL/lock1s, três planos SeqScan+Sort que visitam34 linhas. Os buffers de pai e filho são inclusivos; não devem ser somados. Isso não certifica escala/SLO/T13 ou PostgreSQL16. `LIMIT` sozinho não prova custo limitado; a prova de crescimento e o ajuste de índice têm gates próprios.

Em `retained-growth-one-run1`, ROOT aceitou um teste com histórico STOPPED32/128/512 e dois QUEUED em cada estágio. Os nove planos visitam34/130/514 linhas e 4/12/49 páginas de scan, mantendo SQL/lock1s, três direitos QUEUE e zero ACTIVE. O relatório independente tem SHA-256 `a4f9556334aef529859aa00968718442ab22d25de11ad2a5dc22c1550a4f407b`. Os planos usam SeqScan+Sort; a medição em cache PostgreSQL14 identifica trabalho proporcional ao histórico, sem provar throughput, violação de prazo, SLO/T13 ou PostgreSQL16. ROOT aprovou estudar uma nova migration com índice parcial ordenado; fonte e upgrade ainda dependem de mapa de impacto, revisão e provas. A V19 aplicada não será editada.

A campanha adicional de expiração foi executada e revisada. A revisão inicial de fontes identificou que o cursor esgotado adiava a manutenção até depois do próximo ordinal. O candidato recebeu um retorno imediato ao início da fila, limitado a uma vez por rodada e dentro das mesmas oito consultas. A campanha de quatro focais teve três PASS e uma falha de ordenação temporal do teste; após revisão de fontes, somente esse método foi repetido e passou. ROOT aceitou independentemente os quatro focais combinados em `root-b5b2-queue-expiry-four-combined-independent-review.json` (SHA-256 `5c1df5389de48a11ca9a61dea145a7d918388182c48c2354da3d2fa9181973cf`). Esse aceite cobre a alteração e os dois novos casos, sem encerrar o restante da matriz. As 13 provas acima pertencem à árvore anterior congelada; não certificam automaticamente o bytecode após esse ajuste. As duas skills canônicas de concorrência/prova operacional receberam revisão independente e foram integradas pelo PR715 (main765339eb95632842f2b9b1c5a5fab9f60cd2ed51), com sincronização oficial seletiva e 771 outros arquivos intactos. A documentação final e os casos restantes continuam pendentes; essa entrega não fecha B5b.2.

Depois vêm B5b.3, as provas corporativas/distribuídas restantes, o corpus/receita B6 e o fechamento B7. Angular permanece após o fechamento do backend. Nenhum desses gates é considerado concluído pela primeira bateria do worker.

## Histórico — Ajuste de acesso à fila — fonte V1, sem prova runtime

O mapa V3 foi aceito por ROOT para implementação (parecer SHA-256
`09b34c5f3dfe27fb8d076066b0cd6df5329f5058792686de524eb54e948d93a4`).
A fonte V1 acrescenta V20 com índice parcial ordenado e validação física no lane
owner. A composição de migração foi dividida: DDL até19, recuperação canônica
com validação de cada fase antes de grants, preflight19 COMPLETE e depois20.
O initializer não executa sob a conexão que mantém o mesmo advisory lock.
Isso é código candidato ainda sem compilação/teste; não é garantia comprovada.

A revisão de fonte usa `worker-queue-index-source-freeze-v1.json` SHA-256
`9998cb74f11924a4f30e12f3a57c81667c9574a85a3fd471141476625534b98c`,
22 fontes do delta e V1–19 byte a byte intactas. Seis novos métodos JUnit incluem
nove cenários físicos de drift num método; são fontes, não resultados PASS.
Recusa de drift do índice é requisito owner migrate/validate, sem promessa de
verificação física online por cada operação. As provas de crescimento anteriores
continuam vinculadas às árvores congeladas sem índice; não certificam V20.

Maven/PG aguardam gate independente de fonte. Depois dele: focais de índice e
retomada, regressões de migração/recursos/namespace, planos reais, consumidor
empacotado e atualização documental/skills conforme comportamento comprovado.
Nenhuma release, dependência do host ou Angular mudou neste corte.

A primeira revisão de fontes exigiu corrigir a validação histórica19 e o TCCL
usado pelo Flyway do JAR155. Ambas foram corrigidas na fonte V2, freeze SHA-256
`be5734c2ad3ba8531a36fe3c7458775ec0acf0885ae688829fdf80d90eb8a677`.
O aceite de fontes e as provas PostgreSQL continuam pendentes; essas correções
não são resultados de execução. As fontes V1 e seu parecer permanecem históricos.
