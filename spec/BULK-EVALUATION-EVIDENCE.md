# Evidência protegida de avaliação de domínio

`BulkEvaluationSnapshot` captura fatos e plano de domínio para cada alvo de uma `BulkStoredProposal`. O SDK fecha o vínculo entre entrada, alvos/versões, instante, fatos, plano e evidência de governança capturada pelo host. Não executa a avaliação, não decide disponibilidade ou política e não emite READY/totais públicos. Essa separação evita confundir cobertura estrutural com autorização de negócio.

## Composição

```java
var evidence = new BulkTargetEvidence<>(
    new BulkTarget<>(canonicalWireId, expectedVersion),
    observedVersion,
    encodedMinimalFacts,       // objeto JSON de DTO validado do domínio
    encodedCandidatePlan);     // objeto JSON do plano efetivamente avaliado
var evaluation = new BulkEvaluationSnapshot(
    storedProposal, evaluationInstant, List.of(evidence), capturedGovernance);

// Dentro de uma transação de serviço existente:
proposals.insertEvaluated(evaluation);
var recovered = proposals.findEvaluation(trustedContext, storedProposal.id());
```

O host/provider fornece snapshots mínimos dos fatos que determinam o efeito e do plano que será comparado na revalidação. Preservar BigDecimal/BigInteger exatos; não converter por double. Não capturar estado completo do domínio ou segredos por conveniência. Campos de dependência, revisões e mecanismos de lock/revalidação pertencem ao domínio; colocar um valor arbitrário no objeto não demonstra que sua origem é autoritativa. O SDK valida a representação e a integridade do vínculo, não a verdade dos fatos.

O conjunto deve cobrir exatamente os alvos da intenção EXPLICIT/SYNC. Para PER_ITEM_UPDATE, o dono da seleção continua sendo `items`; nos demais modos, `selection.targets`. IDs usam os quatro codecs canônicos e preservam o tipo wire. Alvo duplicado, estranho, ausente ou com expectedVersion diferente é rejeitado. A ordem da evidência fornecida é normalizada para a ordem da intenção original, incluindo a ordem de `items`.

ObservedVersion é obrigatório e pode divergir de expectedVersion: isso registra um fato de conflito e **não** o torna executável. Se o domínio não consegue obter os fatos de todos os alvos autorizados, não fabrica esta evidência completa; a futura avaliação governada deverá produzir o diagnóstico BLOCKED apropriado. A evidência de política tem campo próprio de governança; não escondê-la em facts/plan nem confundi-la com diagnóstico ou decisão pública.

O instante é truncado a microssegundos e deve pertencer a `[createdAt, expiresAt)`. A API recebe o instante capturado pelo servidor; não consulta relógio durante a reconstrução histórica. Ler evidência de proposta expirada não autoriza confirmação.

## Vínculo e limites

O fingerprint usa framing tipado próprio `praxis.bulk.evaluation/1`, distinto de `praxis.bulk.intent/1`. Ele cobre UUID, fingerprint da intenção original (que já vincula contexto/operação/schema/atomicidade), createdAt/expiresAt, instante de avaliação, todos os alvos/fatos/planos e governança completa (inclusive instantes de observação). Alterar qualquer dimensão muda o hash. Hash não é assinatura contra administradores maliciosos.

Fatos e plano são objetos imutáveis com cópias defensivas, usando os limites numéricos/profundidade do SDK. O conjunto tem teto estrutural de 10.000 alvos e framing/payload de até 8 MiB; o limite da operação pode ser menor. A escrita limita o tamanho inclusive quando caracteres escapados expandem o JSON. O decoder rejeita duplicatas, campos desconhecidos, conteúdo truncado/excedente, perda de precisão, cobertura incorreta ou vínculo/fingerprint incompatível, sem encadear conteúdo privado nas exceções do store.

O payload único é deliberadamente limitado ao recorte EXPLICIT/SYNC. Ele não comprova paginação eficiente de resultados por alvo nem admissão individual em lotes grandes. Antes dessas superfícies, revisar o acesso/indexação com o consumidor real e seus limites. Não reutilizar a projeção pública redigida como fonte protegida.

## Persistência e migração V2

A [migração explícita](BULK-PROPOSAL-STORAGE.md) conserva V1 e acrescenta V2. Em banco novo, migrate aplica duas versões; em banco no V1, aplica uma; repetição aplica zero. A tabela `praxis_bulk_evaluation` possui PK proposal_id e FK imediata composta para `(proposal_id, fingerprint)` da entrada. O pai recebe a UNIQUE correspondente. Checks limitam hash/payload; trigger recusa UPDATE. A validação física verifica também a nova PK/FK, permanência, colunas e trigger.

`insertEvaluated` insere as duas linhas em uma única transação física existente. Falha na segunda inserção impede a persistência da primeira, mesmo quando o chamador captura a exceção e tenta concluir a transação. Ambas só ficam visíveis após commit. O store não oferece attach, UPDATE, sobrescrita ou upsert: uma reavaliação exige nova proposta/UUID. Entradas V1 existentes continuam recuperáveis, mas nunca ganham evidência por migração ou fallback. O ciclo operacional futuro deverá decidir sua expiração/substituição.

`findEvaluation` primeiro recupera a entrada por namespace/usuário/recurso/operação confiáveis; depois verifica o vínculo da evidência com essa entrada. Ausência de evidência retorna vazio; corrupção gera CORRUPT. Grants atuais e redaction de respostas continuam obrigações dos providers/host.

Credenciais de runtime necessitam de USAGE no schema e SELECT/INSERT **nas duas tabelas**. Migração continua explícita, com credenciais próprias no mesmo banco operacional, fora da transação do domínio. Não concede privilégios, cria workers ou registra endpoints/beans. Histórico/checksum V1 é preservado; nenhuma migração reinterpreta ou preenche fatos antigos.

## Provas e gates restantes

`BulkEvaluationSnapshotTest` cobre modalidades/codecs, cobertura/tipos/versões, instante, imutabilidade, limites e framing independente. `BulkEvaluationStorePostgresTest` prova upgrade V1→V2, recuperação, atomicidade, concorrência, FK/trigger/drift, contexto, corrupção e grants em PostgreSQL real. A suíte anterior do store continua cobrindo V1+V2 e sua coexistência com o host.

Config já fornece a resolução operacional tipada e o host possui consumidores unitários. Ainda faltam composição dos providers de lote com fontes reais de grants, resultado/diagnósticos de avaliação governada, projeção READY/BLOCKED derivada e admissão/revalidação no ponto de mutação. Esses gates impedem anúncio prematuro de capability. O backend completo permanece antes da evolução Angular.


## Governança protegida e comparação de recaptura

`BulkEvaluationGovernance(evaluatorRevision, authorizationFingerprint, policies)` é obrigatório. Revisão vem do avaliador canônico e fingerprint da fonte de autorização atual, incluindo todos os grants relevantes para operação/campos/alvos/referências. Um hash das authorities de JWT ou de catálogo estático não prova revogação empresarial. O SDK não implementa nem certifica essa fonte; as fixtures dos testes são explicitamente sintéticas.

Cada `BulkPolicyObservation` contém tenant/environment, coordenada Config (layer/type/key), estado textual canônico, resolutionFingerprint e observedAt. Estado/fingerprint são opacos para Metadata; o host consulta Config e avalia sua resolução completa, inclusive efeito e especialização. Não duplicar enum, cabeça ou payload Config no SDK. NEVER_APPLIED também exige observação positiva e decisão explícita do provider; retirada/falha jamais equivale a lista vazia. Uma observação pode registrar um estado impeditivo sem autorizá-lo.

São aceitas de 1 a 64 coordenadas distintas, ordenadas pelos campos da coordenada. O host valida a completude do conjunto requerido e seu escopo; a infraestrutura não presume que várias leituras Config sejam um snapshot consistente. Qualquer combinação multialvo exige prova do provider. Cada observedAt pertence a `[proposal.createdAt, evaluatedAt]`, normalizado a microssegundos. O namespace e subject do input continuam vinculados ao recurso/operação/schema; o host deve verificar a correspondência desse contexto com tenant/environment de cada política, sem hints de headers do cliente.

`matchesCurrentEvidence(currentContext, checkedAt, currentTargets, currentGovernance)` exige contexto completo idêntico, instante não anterior à avaliação e anterior à expiração. Compara fatos/planos/versões e governança; ignora apenas evaluatedAt/observedAt para que uma leitura nova de conteúdo igual não cause falso stale. Shape inválido continua sendo erro, nunca true; expiração/contexto alterado retornam false. O método não executa lookup e não detecta observação antiga reenviada pelo próprio chamador: só chamar depois de consultas atuais e validação de política, grants, versões, schema/avaliador e dependências. Igualdade é condição necessária, nunca permissão, READY ou prova de segurança contra write skew. O host ainda deve aplicar locks/invariantes e reservar execução/receipts no ponto correto.

Se política, autorização, escopo, schema, avaliador, fatos ou plano mudarem, exigir nova avaliação/proposta. A leitura da política no Config e o commit de domínio permanecem transações separadas; não há revogação distribuída instantânea. O gateway HTTP do host também não é, por si só, identidade autenticada para um worker.

Migração beta do contrato Java: o constructor anterior sem governança foi removido; consumidores devem obter evidência real antes de construir o snapshot. Payloads anteriores sem esse campo são recusados como CORRUPT, mesmo com seu hash antigo válido, sem inventar autorização histórica. O input pode ser recuperado para orientar uma nova avaliação/UUID. SQL e checksums V1/V2 permanecem intactos; nenhuma atualização retroativa de evidência imutável. Não existe runtime de lote admitido que justifique uma trilha paralela de compatibilidade neste corte.

`BulkGovernanceEvidenceTest` cobre recaptura, mudança de contexto/evidência, tempos e integridade do codec. A suite PostgreSQL também prova governança após commit, retirada divergente sem reescrever o registro e rejeição de payload antigo sem binding.
