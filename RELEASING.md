# Releasing — praxis-metadata-starter

Este documento descreve como publicar um Release Candidate (RC) e versões finais no Maven Central usando os workflows deste repositório.

## Pré‑requisitos
- GitHub Secrets (no repositório):
  - `CENTRAL_TOKEN_USER` e `CENTRAL_TOKEN_PASS` (tokens do Sonatype Central Portal)
  - `GPG_PRIVATE_KEY` (chave privada ASCII‑armored ou base64, sem CRLF)
  - `GPG_PASSPHRASE` (passphrase da chave)
  - `GPG_KEY_ID` (opcional; se ausente, o workflow resolve automaticamente)
  - `RELEASE_PAT` (obrigatorio quando o workflow `workflow_dispatch` criar tags; pushes feitos com `GITHUB_TOKEN` nao disparam novo workflow de tag)
  - `NEON_RELEASE_ASSIGNMENT_JSON` (assignment revisado antes do dispatch, sem senha, com SHA-base, tag/versão esperada, janela curta e receipt/hash do control plane da child exclusiva)
  - `NEON_RELEASE_OWNER_URI` (URI da role criada somente na child, com `sslmode=verify-full`; nunca credencial do parent ou produção)
- Java 21 instalado localmente (para builds locais).
- Antes do dispatch, o coordenador cria uma child Neon não protegida e exclusiva, registra receipt recente do control plane e revisa sua ligação a base-main SHA, tag/versão, endpoint/parent e role. A role da child não pode autenticar no parent. O owner deve ter identidade direta (`session_user=current_user`), `NOSUPERUSER`, `CREATEROLE` e `CREATEDB`.
  O guard exige que ninguém possa assumir o owner: zero memberships enraizadas em `pg_auth_members.roleid=owner`, incluindo a closure recursiva; veja [a consulta canônica](src/main/java/org/praxisplatform/uischema/bulk/BulkExecutionMigrator.java) (`validateNoOwnerMembership`) e [o perfil do gate](src/test/java/org/praxisplatform/uischema/bulk/BulkManagedOwnerNeonGateTest.java) (`Assignment.load`).
  Isso difere de `pg_auth_members.member=owner`: a membership do owner administrativo gerenciado em `neon_superuser` é permitida nesse perfil; não autoriza usá-lo como runtime/control plane. Criar o owner pela API/Console/CLI Neon na child e atestar ambas as direções do grafo.
  Uma role criada via SQL por outra role com `CREATEROLE` recebe o grant administrativo da nova role ao criador (`roleid=novo_owner`) e **não** satisfaz essa pré-condição. Confirmar o grafo real antes de atribuir a role, sem remover grants ad hoc ou relaxar o guard. O runner confere esse assignment e a identidade/TLS da conexão; **não** consulta a API Neon. Secrets devem estar prontas antes do dispatch, pois o push da tag inicia o job. Reexecução exige assignment para o novo número de tentativa e uma child adequada.

O JSON do assignment usa `schemaVersion=1`, `repository`, `baseCommit` (SHA-40 da
main antes do dispatch), `expectedTag`, `runAttempt`, `validFrom`, `expiresAt`,
`owner` (`role`, `database`, `createdOnChild=true`) e `controlPlane` com
`observedAt`, `branch` (`id`, `project_id`, `parent_id`, `protected=false`) e
`endpoint` (`id`, `project_id`, `branch_id`, `host`, `type=read_write`,
`pooler_enabled` conforme observado, `true` ou `false`). Esse campo informa a
disponibilidade de pooling no compute; a conexão direta é fixada pela igualdade
da URI com `endpoint.host` sem `-pooler`. `controlPlaneSha256` é o SHA-256 do objeto
`controlPlane` em JSON canônico (`sort_keys=true`, separadores compactos,
UTF-8). O coordenador conserva o recibo bruto de onde extraiu esses campos e a
revisão independente; o JSON sozinho não atesta que o runner consultou Neon.
O script exige `HEAD==baseCommit` ou exatamente um commit filho cujo único
delta no POM é a versão raiz esperada. A URI secreta precisa usar o host direto
do endpoint, a role e o banco atribuídos e `sslmode=verify-full`; o teste remoto
atesta PG/identidade real. O assignment expira em até quatro horas e uma nova
tentativa do workflow não pode reutilizar silenciosamente a anterior. Antes da
fase Neon, o job revalida a mesma atribuição e exige 90 minutos restantes. A
projeção pública whitelist da atribuição é preservada no artefato de custódia;
ela inclui branch/parent/endpoint/owner e hashes, mas nenhuma URI ou senha.

## Prova focal sem publicação

O dispatch do mesmo workflow com `create_tag=false` executa a matriz de fonte
exata: `BulkManagedOwnerNeonGateTest` primeiro, `BulkOptionalPoliciesNeonTest`
depois e a fase `resource-contracts` com `CustomFieldsResourceE2ETest` e
`CompositeIdResourceE2ETest`, na mesma child exclusiva inicialmente sem roles bulk.
P4 exige também a leitura pública autorizada COMPLETE por `readInspectionBatch`,
com totais e negação de escopo; a fase de recursos exige todos os seis métodos HTTP. Não cria tag, altera versão, assina ou publica; tampouco aprova
o inventário por impacto ou o artefato da release.
Use esse caminho quando a topologia do computador local impede uma prova remota
representativa; registre que CI→Neon não certifica automaticamente Render→Neon.

Prepare os secrets separados `NEON_PROOF_ASSIGNMENT_JSON` e
`NEON_PROOF_OWNER_URI`. A atribuição usa `purpose=proof`, `baseCommit` e
`exactSourceCommit` iguais ao SHA exato despachado; não contém `expectedTag`.
Demais guards de child, role criada exclusivamente nela, TLS, tentativa e janela
são iguais aos da release. O job de 90 minutos exige 95 minutos restantes ao
iniciar e revalida a mesma atribuição com 40 minutos restantes antes de P4;
nenhum resume silencioso ou credencial herdada do parent é permitido.
O harness cria arquivos privados separados para as credenciais P4 e para as duas
roles ordinárias dos recursos fora do checkout; o arquivo de recursos e seu journal
de OIDs usam caminhos absolutos, modo `0600` e não entram no artifact.
O job não transmite Maven bruto ao console; scanner verifica logs/XML contra
credenciais owner, P4 e recursos antes do upload, inclusive em falhas. Os três
diretórios de relatórios Surefire ficam separados; casos obrigatórios, classes/recursos antes
e depois, fontes e dependências resolvidas compõem um recibo focal com
`releaseApproved=false`. A falha continua sendo falha mesmo quando a custódia é
preservada. O inventário e a custódia do JAR seguem gates separados da tag.

## Limite do perfil sintético SDK em P4

O perfil `assertOrderedTransportProfile` exige saldo pré-policy **estritamente
maior que 1500 ms**, dentro do mesmo prazo produtivo absoluto de cinco segundos.
Essa garantia é menor que a reserva sintética anterior de mais de 2100 ms. Ela
não comprova a reserva independente do pool Config do Quickstart: esse consumidor
continua exigindo mais de 2100 ms (pool de 1 s, scheduling de 100 ms e SQL de 1 s),
com prova HTTP própria vinculada ao artefato adotado.

A constante do teste deriva 1500 ms de onze executes restantes com piso de 100 ms
(seis leituras sintéticas, um batch pós-admissão, três executes de mutação e um batch
de receipt) mais 400 ms de tolerância para overhead. Os 28 executes anteriores
mantêm seus pisos: `5000 - 28×100 - 1500 = 700 ms` de folga externa estrita antes
do guard. Isso é tolerância do harness, não garantia de latência do ambiente.
Permanecem obrigatórios os 39 executes, conclusão `COMPLETED`, um efeito, um receipt,
replay sem nova callback e todos os negativos de prazo/rollback/fencing. Nenhum
prazo, ancoragem, timeout de SQL/aquisição ou guard produtivo foi alterado.

O marker exato agora é
`NEON_PROOF_ORDERED_UNIT_TRANSPORT=sdk-synthetic,5s-unit,100ms-floor,pre-policy-reserve-over-1500ms,39-executes,one-domain-effect-and-receipt,completed,replay-without-callback`.
O verificador recusa o marker anterior como evidência da árvore atual. Recibos e
runs históricos preservam seus limites originais; a mudança não converte uma
falha anterior em PASS. A release continua exigindo P4 Neon fresco na fonte atual.

## Fluxo (Release Candidate)
1) Validar localmente o mecanismo offline (sem banco nem assinatura):
```
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/release -p 'test_*.py' -v
python3 tools/release/neon_gate.py
```
O inventário `tools/release/neon-test-inventory.json` deve estar fechado para a
árvore integrada: todo teste/recurso/fixture rastreado é enumerado e as células
B–H apontam para casos Neon e asserções reais; `requiredResourceContractCases`
fixa adicionalmente os cinco métodos custom-field e o método integral composite-ID.
Os quatro tipos de seleção são `no_db`, `managed-owner`, `optional-policies` e
`resource-contracts`. `db_local_legacy` ou
`external_workspace` pendente, classe desconhecida, skip ou evidência divergente
bloqueiam a release. Exclusões fora do impacto exigem revisão do diff/callpath;
somente reuso que sustenta uma obrigação A–I precisa de artefato bruto e digest
do escopo produtivo/dependências. `EmbeddedPostgresColdStartTest` é prova
histórica de extração local e não integra a validação remota; sua substituição
no inventário deve apontar a caso Neon B executável e à custódia real do JAR.
Não executar `clean verify` integral enquanto fixtures H2/Embedded permanecerem
na árvore: testes dependentes de banco usam exclusivamente uma child Neon.
As células D/E/F/G apontam ao caso P4 com asserções catalogadas. A disposição
das fixtures locais está vinculada ao inventário e aos digests do escopo
revisado; antes da tag, ainda são obrigatórias a prova remota do mesmo commit
e a custódia do JAR. Uma seleção de classes compilável não autoriza a release.
O teste de paridade JsonLogic/Angular não pode ser contado como verde no runner
quando o workspace Angular está ausente e seu `assumeTrue` o salta; sua
disposição por impacto cobre apenas a matriz antiga de operadores inalterada.

O ciclo Maven prepara os SDKs históricos usados pelas provas de migração em
`generate-test-resources`, pelo `download-maven-plugin` com URL de Maven Central,
SHA-256 fixado, `alwaysVerifyChecksum=true` e `failOnError=true`. A prova histórica V19 usa
`target/historical-bulk-sdk/metadata-v19-rc155.jar`; o teste resolve esse caminho
por padrão e ainda valida hash, CodeSource e migrations do SDK nativo.
Não é necessário fornecer `praxis.bulk.historical.rc155.jar` no release workflow.
Uma property explícita continua disponível para provas históricas controladas,
sem desativar a validação de bytes. JAR ausente/divergente ou download falho
bloqueiam a prova selecionada: não instalar a coordenada pública atual como
fixture nem encaminhar `target/classes` para substituir o SDK histórico.

2) Para qualquer mudanca de contrato publico, executar o gate corporativo antes
   da tag:
```
# Substituir pela última tag efetivamente publicada no Maven Central.
scripts/check-public-contract-gate.sh --base <ultima-tag-publicada>
```

Esse gate e obrigatorio quando a mudanca toca `x-ui`, `/schemas/filtered`,
`/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities`,
anotacoes publicas, enriquecimento OpenAPI, headers, ETag, `X-Schema-Hash` ou
controladores/base publicos.

Na preparação de uma release já integrada à main, comparar com `origin/main`
produz diff vazio e não cobre o contrato acumulado desde a publicação anterior.
Confirmar a tag anterior no Git e sua coordenada no Maven Central, registrar o
SHA/base e executar também `git diff --check <ultima-tag-publicada>..HEAD`.
O passo do workflow sem `--base` verifica somente higiene do checkout; não
substitui esta revisão acumulada nem executa as provas downstream listadas.

Checklist minima para esse caso:
- usar nova tag/versao do starter ou instalar localmente o artefato alterado de
  forma controlada antes de validar consumidores;
- validar `praxis-api-quickstart` contra exatamente esse artefato;
- revisar docs/examples que espelham o contrato publico;
- registrar comandos executados e escopo nao validado;
- garantir que `target/**`, `.flattened-pom.xml`, `.m2repo/` e artefatos
  gerados de release nao entram no change set.

3) Em `main`, executar o workflow de release por `workflow_dispatch`, com
   `create_tag=true` e versao explicita ou bump/preid. A preparacao persiste
   o POM e envia commit/tag atomicamente; nao cria tag sobre POM divergente.
4) Acompanhar o workflow “Release Java Starter (praxis-metadata-starter)”
- O workflow resolve a versão a partir da tag (`v` é removido → `1.0.0-rc.6`).
- Passos: conferir ancestralidade e versão persistida → higiene pública → importar GPG → validar assignment/inventário → `clean test` da seleção sem DB → revalidar child/janela → `test` da classe managed-owner B/C/H **primeiro** → `verify` da classe optional-policies P4 na mesma child e árvore, com assinatura → `test` da fase `resource-contracts` com duas classes e seis métodos obrigatórios → verificar quatro diretórios XML/logs, casos, snapshots de classes de teste/produção e JAR staged → construir/validar o ZIP → preservar o arquivo → upload único pela API oficial e verificação dos bytes públicos. Falha ou cleanup incompleto de B/C/H interrompe a sequência antes de P4.
- O wrapper Maven 3.9.6, a seleção sem DB completa e auditada, o gate Neon por impacto e `check-public-contract-gate.sh` são obrigatórios. Não se afirma suíte integral nem equivalência das fixtures históricas não executadas. Os relatórios Surefire são separados e limpos por fase; erro, skip, log ausente, receipt fora da árvore/tag ou JAR diferente bloqueiam antes do upload. As invocações Neon não usam `clean`; o script confere os bytes de classes/recursos testados e empacotados. O bundle usa o mesmo JAR staged, sem rebuild posterior. P4 cria arquivo privado de duas senhas de fixture em `$RUNNER_TEMP` com modo `0600`; o scanner rejeita sua presença em logs, XML ou custódia antes do upload, e o arquivo nunca entra no artefato. A fase de recursos gera duas novas senhas ordinárias em arquivo separado; seu scanner cobre também logs e XML de todas as fases. O setup owner cria database `template0`/schema distintos por classe e concede CONNECT/USAGE/CREATE; a aplicação opera com a role ordinária, PG17 e TLS verificados antes do Hibernate. Spring fecha o contexto por DirtiesContext antes do listener remover os OIDs atribuídos; cleanup incerto bloqueia a execução, sem PASS ou remoção apenas pelo nome.
- O receipt vincula `run_id`/tentativa, base/tag, assignment público e hash do segredo original, versão de Java/Maven, POM efetivo, árvore de dependências e SHA-256 dos JARs no classpath de teste resolvido. As três consultas Maven de custódia não executam testes nem recompilam o JAR. A passphrase GPG entra somente pelo ambiente `MAVEN_GPG_PASSPHRASE` suportado pelo plugin 3.2.4; não é passada por `-Dgpg.passphrase`.
- O publicador exige `PUBLISHED` para o GAV esperado e compara POM/JAR públicos e SHA-512 com o ZIP validado em `repo.maven.apache.org`; HTTP 200 ou uma instalação local não bastam.
- Consumidores só são atualizados após essa prova; o host ainda deve executar seu próprio `verify` com dependências públicas sem override.

5) Verificar artefatos assinados no job:
- `target/praxis-metadata-starter-1.0.0-rc.6.jar(.asc)`
- `*-sources.jar(.asc)` e `*-javadoc.jar(.asc)`

6) Acompanhar a publicação e preservar sua custódia
- `central_bundle.py` seleciona oito entradas primárias reais: POM flattened, JAR principal/sources/javadoc e quatro assinaturas ASC. O ZIP contém essas entradas e seus quatro checksums (40 arquivos) no único diretório versionado Maven, sem metadata de repositório ou POM artificial no nível artifactId.
- A validação separada rejeita caminhos extras/duplicados/unsafe, GAV incorreto no POM/JAR, checksums incorretos e assinaturas que não sejam válidas para a chave esperada. GPG real é obrigatório no job oficial; fixtures offline não provam criptografia.
- O artifact `central-validated-bundle-RUN_ID` preserva o ZIP e os recibos antes do upload. `publish_central.py` envia os mesmos bytes/hash uma única vez, sem redirect autenticado; grava e sincroniza o UUID antes de consultar o status. Credenciais ficam somente no passo de upload, nunca nos recibos.
- O job mantém 90 minutos, medidos desde o primeiro passo. Publicação e disponibilidade compartilham o tempo restante, reservando 180 segundos para o artifact final. Há recusa antes do upload se restarem menos de 120 segundos úteis; a operação completa open/read usa timer POSIX no main thread do próprio Python (máximo60s ou saldo menor), restaura o handler e recusa timer preexistente; o retorno é rechecado contra a deadline. Esse guard complementa o socket timeout; cada chamada é limitada a 60 segundos ou ao saldo menor e cada espera a 15 segundos ou ao saldo menor. Não existem janelas independentes de 30+40 minutos nem garantia de propagação do Central.
- `central-publication-custody-RUN_ID-ATTEMPT` preserva o resultado inclusive em falha. Resultado desconhecido, rejeição, bytes públicos divergentes ou orçamento esgotado impedem declarar adoção; conservar deployment/ZIP/tag e reconciliar a mesma tentativa, sem reupload ou mover tags. Um rerun não libera novo upload.
- Metadata rc.156 falhou antes de publicar por asserts históricos de JDK corrigidos no PR277. A rejeição do bundle Config rc.160 não prova defeito idêntico no Metadata. Esta mudança estabelece custódia explícita neste ciclo próprio; não reescreve os recibos históricos.

## Fluxo (Versão Final)
- Mesmo dispatch, com versao estavel sem sufixo RC; nao criar tag manual como atalho.

## Observações
- O `pom.xml` da tag e de main persiste a versao publicada; divergencia falha antes de publicar.
- O flatten POM no perfil `release` remove o parent e gera um POM compatível com Central.
- Para publicar a documentação (Javadoc HTML + markdown convertido para HTML):
  - O workflow reutilizavel “Documentation” roda apos a release verde; commits em main nao publicam docs. Para corrigir apenas a documentação de uma versão já publicada, integre a correção em `main` e execute manualmente `Documentation` (`docs.yml`) selecionando `main`. Esse dispatch só constrói Javadoc, verifica os links locais e publica `gh-pages`; não cria tag, repete a publicação do SDK nem executa a matriz Neon. Em outro ref, o job fica inativo.

## Troubleshooting
- GPG key id não resolvido:
  - Garanta que `GPG_PRIVATE_KEY` está sem BOM/CRLF. O workflow já sanitiza; ver logs da etapa “Import GPG private key”.
- Falha no upload/status:
  - Verifique a etapa oficial e o recibo sanitizado `deployment.json`, preservando UUID e ZIP. Tokens só são injetados no passo de upload, não como server Maven global.
  - Upload de resultado desconhecido requer reconciliação; não repetir a release para descobrir o resultado.
- Disponibilidade pública falhou:
  - POM/JAR ausentes, diferentes do bundle ou sem SHA-512 correspondente não autorizam atualizar o consumidor. Preservar a publicação já confirmada e seu UUID.
  - Orçamento esgotado não cancela automaticamente um deployment; verificar a mesma coordenada quando houver evidência de propagação, sem novo upload. Esta prova não substitui `verify`/HTTP protegido do host nem migrations/deploy.
- Assinaturas ausentes:
  - Confirme execução com `-P release -Dgpg.skip=false`; o job usa isso por padrão.

### Preflight de custódia antes de criar a tag

Antes do dispatch que cria a tag, audite as quatro seleções em uma árvore limpa do
commit revisado. Use o SHA completo resolvido da fonte como `--base`, sua versão
atual do POM como `--version`, e `--release --require-clean --print-selection`
para `no_db`, `managed-owner`, `optional-policies` e `resource-contracts`. Não passe a futura versão
antes que o commit oficial de versão exista. Esse preflight não conecta ao banco,
não cria tag e não substitui os testes e a assinatura obrigatórios da publicação.

Uma alteração de teste exige renovar seu hash inventariado. Uma mudança no scope
amplo de produção ou no POM exige revisão das exclusões e reutilizações, mesmo
quando o caminho exercitado não mudou. Não atualizar hashes sem confrontar o diff,
o comportamento coberto, as substituições obrigatórias e os recibos originais.
A validação da tag compara essas closures contra o base pré-release; a alteração
apenas da versão pelo dispatcher é verificada separadamente.

No incremento de recuperação cold-node, foram revisados cinco scopes de exclusão
(86 entradas) e três reutilizações históricas contra rc166. Apenas o hint de geração
fenced e a versão do projeto mudaram nesses scopes; SQL, ACL, workers, callback,
producer e dependências permanecem iguais. O teste de leitura tem 23 casos, dos
quais dez cobrem o novo hint. Os ZIPs históricos, classificações e substituições
permanecem intactos e não certificam o hint, os readers atuais ou a adoção pública.

A tag rc167 foi criada pelo fluxo oficial, mas sua publicação pendente foi
cancelada antes de executar por custódia desatualizada. Preserve a tag imutável e
registre a correção; a próxima publicação deve usar uma versão livre. Não retag,
substitua bytes públicos ou trate o cancelamento como publicação bem-sucedida.


### Reconciliação resource contracts de 2026-10-27

O [registro causal](docs/technical/metadata-resource-contract-release-impact-20261027.md)
explica as novas fontes, hashes e perfis, as 86 disposições legadas e os três reusos
históricos. O status `impact_review_pending` admite a prova focal de fonte, que não
publica; bloqueia o gate de publicação até o aceite independente da nova prova P4.
O job de criação da tag não executa esse gate: antes de dispará-lo, o coordenador
deve executar o preflight do inventário na fonte revisada e confirmar o aceite.
Não substituir esse estado por hashes renovados sem a análise das chamadas.
O recibo focal enumera B–H e os seis casos de recurso, com
`releaseApproved=false`; P4 ou recurso ausente, skip ou marcador divergente falha.
