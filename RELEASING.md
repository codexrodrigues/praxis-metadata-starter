# Releasing — praxis-metadata-starter

Este documento descreve como publicar um Release Candidate (RC) e versões finais no Maven Central usando os workflows deste repositório.

## Pré‑requisitos
- GitHub Secrets (no repositório):
  - `CENTRAL_TOKEN_USER` e `CENTRAL_TOKEN_PASS` (tokens do Sonatype Central Portal)
  - `GPG_PRIVATE_KEY` (chave privada ASCII‑armored ou base64, sem CRLF)
  - `GPG_PASSPHRASE` (passphrase da chave)
  - `GPG_KEY_ID` (opcional; se ausente, o workflow resolve automaticamente)
  - `RELEASE_PAT` (obrigatorio quando o workflow `workflow_dispatch` criar tags; pushes feitos com `GITHUB_TOKEN` nao disparam novo workflow de tag)
- Java 21 instalado localmente (para builds locais).

## Fluxo (Release Candidate)
1) Validar localmente (sem assinar):
```
./mvnw -B -T 1C clean verify
./mvnw -B javadoc:javadoc && test -d target/site/apidocs
```
O gate inclui `EmbeddedPostgresColdStartTest`, que extrai e inicia PostgreSQL em
diretório novo com resolver independente. Cache Maven isolado não equivale a
cache de binários vazio: uma instalação já extraída pode ocultar incompatibilidade
entre Commons Compress e Commons Lang. Antes de ajustar dependências, conferir
`mvn dependency:tree`; preservar o alinhamento também no classpath de runtime e
na adoção do host, sem corrigir somente o classpath dos testes.

O ciclo Maven prepara os SDKs históricos usados pelas provas de migração em
`generate-test-resources`, pelo `download-maven-plugin` com URL de Maven Central,
SHA-256 fixado, `alwaysVerifyChecksum=true` e `failOnError=true`. A prova V19 usa
`target/historical-bulk-sdk/metadata-v19-rc155.jar`; o teste resolve esse caminho
por padrão e ainda valida hash, CodeSource e migrations do SDK nativo.
Não é necessário fornecer `praxis.bulk.historical.rc155.jar` no release workflow.
Uma property explícita continua disponível para provas históricas controladas,
sem desativar a validação de bytes. JAR ausente/divergente ou download falho
bloqueiam o gate: não excluir testes, instalar a coordenada pública atual como
fixture, ou encaminhar `target/classes` para substituir o SDK histórico.

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
- Passos: conferir ancestralidade e versão persistida → hygiene público → importar GPG → testes e assinatura com `./mvnw -P release ... clean verify` → construir/validar o ZIP → preservar o arquivo → upload único pela API oficial e verificação dos bytes públicos.
- O wrapper Maven 3.9.6, a suíte completa e o gate `check-public-contract-gate.sh` permanecem obrigatórios. Não repetir o build para construir o bundle.
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
  - O workflow reutilizavel “Documentation” roda apos a release verde; commits em main nao publicam docs.

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
