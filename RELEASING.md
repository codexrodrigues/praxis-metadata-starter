# Releasing — praxis-metadata-starter

Este documento descreve como publicar um Release Candidate (RC) e versões finais no Maven Central usando os workflows deste repositório.

## Pré-requisitos
- GitHub Secrets (no repositório):
  - `CENTRAL_TOKEN_USER` e `CENTRAL_TOKEN_PASS` (tokens do Sonatype Central Portal)
  - `GPG_PRIVATE_KEY` (chave privada ASCII-armored ou base64, sem CRLF)
  - `GPG_PASSPHRASE` (passphrase da chave)
  - `GPG_KEY_ID` (opcional; se ausente, o workflow resolve automaticamente)
  - `RELEASE_PAT` (obrigatório quando o workflow `workflow_dispatch` criar tags; pushes feitos com `GITHUB_TOKEN` não disparam novo workflow de tag)
  - Não são necessários segredos de banco remoto, branches efêmeras ou credenciais de provedores em nuvem para publicar a biblioteca.
- Java 21 instalado localmente (para builds e validações locais).

## Validação local pré-release
1) Validar localmente o empacotamento offline e a suíte unitária/contrato da biblioteca:
```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/release -p 'test_*.py' -v
bash ./mvnw -B -P release-unit clean test
```
O perfil `release-unit` executa toda a suíte unitária e de contrato sem dependências de banco externo/embutido ou workspace externo, e pula o download repetido dos JARs históricos em `generate-test-resources`.

2) Para qualquer mudança de contrato público, executar o gate corporativo antes da tag:
```bash
# Substituir pela última tag efetivamente publicada no Maven Central.
scripts/check-public-contract-gate.sh --base <ultima-tag-publicada>
```

Esse gate é obrigatório quando a mudança toca `x-ui`, `/schemas/filtered`, `/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities`, anotações públicas, enriquecimento OpenAPI, headers, `ETag`, `X-Schema-Hash` ou controladores/base públicos.

Na preparação de uma release já integrada à `main`, comparar com `origin/main` produz diff vazio e não cobre o contrato acumulado desde a publicação anterior. Confirmar a tag anterior no Git e sua coordenada no Maven Central, registrar o SHA/base e executar também `git diff --check <ultima-tag-publicada>..HEAD`. O passo do workflow sem `--base` verifica somente higiene do checkout; não substitui essa revisão acumulada.

Checklist mínima para mudança de contrato público:
- usar nova tag/versão do starter ou instalar localmente o artefato alterado de forma controlada antes de validar consumidores;
- validar consumidores aplicáveis (como `praxis-api-quickstart` e `praxis-ergon-showcase`) contra exatamente esse artefato;
- revisar docs/examples que espelham o contrato público;
- registrar comandos executados e qualquer escopo não validado;
- garantir que `target/**`, `.flattened-pom.xml`, `.m2repo/` e artefatos gerados de release não entram no change set.

## Fluxo (Release Candidate e Versão Final)
1) Em `main`, executar o workflow `CI and Release Java Starter (praxis-metadata-starter)` (`.github/workflows/release.yml`) por `workflow_dispatch`, mantendo `create_tag=true` e informando `version` explícita ou `bump` (`patch`, `minor`, `major`, `prerelease`) / `preid` (`rc`, `alpha`, `beta`).
   - O job `create_tag` exige `main`, verifica o gate de higiene pública, atualiza o `pom.xml` com a versão resolvida e envia o commit + tag `vX.Y.Z...` atomicamente.
   - Para versão final, usar o mesmo dispatch com versão estável sem sufixo RC; não criar tag manual como atalho.

2) Acompanhar o job `Publish to Maven Central` (`release`):
   - Confere ancestralidade em `main`, versão persistida no `pom.xml` e `scripts/check-public-contract-gate.sh`.
   - Importa a chave GPG e executa `tools/release/test_*.py`.
   - Executa `bash ./mvnw -B -P release,release-unit -Dgpg.skip=false -Dgpg.keyname="${GPG_KEY_ID}" clean verify`, gerando e assinando o JAR principal, `-sources.jar`, `-javadoc.jar` e `.flattened-pom.xml`.
   - `central_bundle.py` constrói e valida de forma independente o ZIP (`central-bundle.zip`) com as 8 entradas primárias assinadas e seus 4 checksums (40 arquivos, sem metadata de repositório), gravando `builder.json` e `validation.json`.
   - Preserva `central-validated-bundle-${{ github.run_id }}` antes de qualquer chamada de rede.
   - `publish_central.py --budget-minutes 30 --wait-for accepted` realiza upload único sem redirects autenticados, persiste o `deploymentId` imediatamente em `deployment.json` e aguarda o status atingir `VALIDATED`/`PUBLISHING` (ou `PUBLISHED`) dentro do orçamento do job de 30 minutos (reservando 180s para custódia final).
   - Preserva `central-publication-custody-${{ github.run_id }}-${{ github.run_attempt }}` mesmo em caso de falha (`if: always()`).
   - Em seguida, o job `documentation` (`.github/workflows/docs.yml`) gera Javadoc/HTML e publica no GitHub Pages (`gh-pages`).

## Adoção pelos projetos hosts
- **Confirmação de disponibilidade pública:** como o workflow encerra após o aceite (`VALIDATED`/`PUBLISHING`) no Central Portal, confirme primeiro que o POM, o JAR e os checksums `.sha512` já estão disponíveis em:
  `https://repo.maven.apache.org/maven2/io/github/codexrodrigues/praxis-metadata-starter/<versao>/`
  antes de atualizar consumidores (`pom.xml` de `praxis-ergon-showcase`, `praxis-api-quickstart`, etc.). Não republique uma versão por demora de propagação do CDN.
- **Responsabilidade de integração real:** testes de integração contra PostgreSQL gerenciado (Neon, RDS, Azure PostgreSQL, on-premise), latência de rede, perfil real de memória/heap e fluxos HTTP/E2E completos devem rodar nos projetos hosts consumidores da biblioteca.
- **Imutabilidade de coordenada:** nunca reutilizar ou mover uma tag publicada ou em publicação; correções pós-tag avançam para a próxima coordenada (`vX.Y.Z-rc.(N+1)`).

## Observações
- O `pom.xml` da tag e de `main` persiste a versão publicada; divergência falha antes de empacotar ou publicar.
- O flatten POM no perfil `release` remove o parent e gera um POM compatível com Maven Central.
- Para corrigir apenas a documentação de uma versão já publicada, integre a correção em `main` e execute manualmente `Documentation` (`docs.yml`) selecionando `main`. Esse dispatch apenas constrói Javadoc, verifica links locais e publica `gh-pages`.

## Troubleshooting
- **GPG key id não resolvido:**
  - Garanta que `GPG_PRIVATE_KEY` está sem BOM/CRLF. O workflow já sanitiza; verifique os logs da etapa `Import GPG private key (sanitize + import)`.
- **Falha no upload ou status:**
  - Baixe `central-publication-custody-*` e inspecione `deployment.json` junto ao `deploymentId` e ao `central-bundle.zip`. Nunca repita upload em nova tentativa do workflow (`GITHUB_RUN_ATTEMPT != 1` é bloqueado por segurança); reconcilie o `deploymentId` existente no Sonatype Central Portal.
- **Verificação explícita de propagação pública (modo completo):**
  - Quando quiser aguardar e conferir byte a byte POM, JAR e `.sha512` em `repo.maven.apache.org` via script, execute `tools/release/publish_central.py` com `--wait-for published` (modo padrão fora do `release.yml`), ou consulte diretamente a URL pública da coordenada no Maven Central.
- **Assinaturas ausentes:**
  - Confirme execução com `-P release -Dgpg.skip=false`.
