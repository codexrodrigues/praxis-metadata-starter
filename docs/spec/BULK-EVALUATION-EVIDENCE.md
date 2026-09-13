# Evidência protegida de avaliação de domínio

`BulkEvaluationSnapshot` captura fatos e plano de domínio para cada alvo de uma `BulkStoredProposal`. O SDK fecha o vínculo entre entrada, alvos/versões, instante, fatos e plano. Não executa a avaliação, não decide disponibilidade ou política e não emite READY/totais públicos. Essa separação evita confundir cobertura estrutural com autorização de negócio.

## Composição

```java
var evidence = new BulkTargetEvidence<>(
    new BulkTarget<>(canonicalWireId, expectedVersion),
    observedVersion,
    encodedMinimalFacts,       // objeto JSON de DTO validado do domínio
    encodedCandidatePlan);     // objeto JSON do plano efetivamente avaliado
var evaluation = new BulkEvaluationSnapshot(
    storedProposal, evaluationInstant, List.of(evidence));

// Dentro de uma transação de serviço existente:
proposals.insertEvaluated(evaluation);
var recovered = proposals.findEvaluation(trustedContext, storedProposal.id());
```

O host/provider fornece snapshots mínimos dos fatos que determinam o efeito e do plano que será comparado na revalidação. Preservar BigDecimal/BigInteger exatos; não converter por double. Não capturar estado completo do domínio ou segredos por conveniência. Campos de dependência, revisões e mecanismos de lock/revalidação pertencem ao domínio; colocar um valor arbitrário no objeto não demonstra que sua origem é autoritativa. O SDK valida a representação e a integridade do vínculo, não a verdade dos fatos.

O conjunto deve cobrir exatamente os alvos da intenção EXPLICIT/SYNC. Para PER_ITEM_UPDATE, o dono da seleção continua sendo `items`; nos demais modos, `selection.targets`. IDs usam os quatro codecs canônicos e preservam o tipo wire. Alvo duplicado, estranho, ausente ou com expectedVersion diferente é rejeitado. A ordem da evidência fornecida é normalizada para a ordem da intenção original, incluindo a ordem de `items`.

ObservedVersion é obrigatório e pode divergir de expectedVersion: isso registra um fato de conflito e **não** o torna executável. Se o domínio não consegue obter os fatos de todos os alvos autorizados, não fabrica esta evidência completa; a futura avaliação governada deverá produzir o diagnóstico BLOCKED apropriado. Campos de status/diagnósticos/políticas não são acrescentados incidentalmente a este contrato de evidência.

O instante é truncado a microssegundos e deve pertencer a `[createdAt, expiresAt)`. A API recebe o instante capturado pelo servidor; não consulta relógio durante a reconstrução histórica. Ler evidência de proposta expirada não autoriza confirmação.

## Vínculo e limites

O fingerprint usa framing tipado próprio `praxis.bulk.evaluation/1`, distinto de `praxis.bulk.intent/1`. Ele cobre UUID, fingerprint da intenção original (que já vincula contexto/operação/schema/atomicidade), createdAt/expiresAt, instante de avaliação e todos os alvos/fatos/planos. Alterar qualquer dimensão muda o hash. Hash não é assinatura contra administradores maliciosos.

Fatos e plano são objetos imutáveis com cópias defensivas, usando os limites numéricos/profundidade do SDK. O conjunto tem teto estrutural de 10.000 alvos e framing/payload de até 8 MiB; o limite da operação pode ser menor. A escrita limita o tamanho inclusive quando caracteres escapados expandem o JSON. O decoder rejeita duplicatas, campos desconhecidos, conteúdo truncado/excedente, perda de precisão, cobertura incorreta ou vínculo/fingerprint incompatível, sem encadear conteúdo privado nas exceções do store.

O payload único é deliberadamente limitado ao recorte EXPLICIT/SYNC. Ele não comprova paginação eficiente de resultados por alvo nem admissão individual em lotes grandes. Antes dessas superfícies, revisar o acesso/indexação com o consumidor real e seus limites. Não reutilizar a projeção pública redigida como fonte protegida.

## Persistência e migração V2

A [migração explícita](BULK-PROPOSAL-STORAGE.md) conserva V1 e acrescenta V2. Em banco novo, migrate aplica duas versões; em banco no V1, aplica uma; repetição aplica zero. A tabela `praxis_bulk_evaluation` possui PK proposal_id e FK imediata composta para `(proposal_id, fingerprint)` da entrada. O pai recebe a UNIQUE correspondente. Checks limitam hash/payload; trigger recusa UPDATE. A validação física verifica também a nova PK/FK, permanência, colunas e trigger.

`insertEvaluated` insere as duas linhas em uma única transação física existente. Falha na segunda inserção impede a persistência da primeira, mesmo quando o chamador captura a exceção e tenta concluir a transação. Ambas só ficam visíveis após commit. O store não oferece attach, UPDATE, sobrescrita ou upsert: uma reavaliação exige nova proposta/UUID. Entradas V1 existentes continuam recuperáveis, mas nunca ganham evidência por migração ou fallback. O ciclo operacional futuro deverá decidir sua expiração/substituição.

`findEvaluation` primeiro recupera a entrada por namespace/usuário/recurso/operação confiáveis; depois verifica o vínculo da evidência com essa entrada. Ausência de evidência retorna vazio; corrupção gera CORRUPT. Grants atuais e redaction de respostas continuam obrigações dos providers/host.

Credenciais de runtime necessitam de USAGE no schema e SELECT/INSERT **nas duas tabelas**. Migração continua explícita, com credenciais próprias no mesmo banco operacional, fora da transação do domínio. Não concede privilégios, cria workers ou registra endpoints/beans. Histórico/checksum V1 é preservado; nenhuma migração reinterpreta ou preenche fatos antigos.

## Provas e gates restantes

`BulkEvaluationSnapshotTest` cobre modalidades/codecs, cobertura/tipos/versões, instante, imutabilidade, limites e framing independente. `BulkEvaluationStorePostgresTest` prova upgrade V1→V2, recuperação, atomicidade, concorrência, FK/trigger/drift, contexto, corrupção e grants em PostgreSQL real. A suíte anterior do store continua cobrindo V1+V2 e sua coexistência com o host.

Ainda faltam resolução operacional autoritativa de políticas no Config (B1-C), composição dos providers, resultado/diagnósticos de avaliação governada, projeção READY/BLOCKED derivada e admissão/revalidação. Esses gates impedem anúncio prematuro de capability. O backend completo permanece antes da evolução Angular.
