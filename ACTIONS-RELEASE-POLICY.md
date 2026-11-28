# Actions no fechamento de versões


O padrão durante desenvolvimento é zero execuções remotas. Valide localmente o escopo alterado; commits, PRs, documentação interna e conclusão de tarefas não são motivos para iniciar Actions. Use os workflows manuais somente no fechamento autorizado de uma versão/publicação. Não use `[skip ci]` como mecanismo principal nem desabilite checks/proteções para economizar.

Antes de push, tag ou dispatch, confira os gatilhos reais de `.github/workflows/`. Tags de release publicam artefatos: não criá-las para testar a automação. Diagnostique localmente antes de repetir um job; conserve a evidência da revisão e dos artefatos usados. Monitores operacionais explicitamente mantidos são independentes do CI de commits. Consulte [RELEASING.md](RELEASING.md) para os pontos de entrada e recuperação.

## Fluxo deste repositório

`release.yml` é iniciado por dispatch em `main` com `create_tag=true` e `RELEASE_PAT` configurado. O job `create_tag` valida o gate de higiene pública (`scripts/check-public-contract-gate.sh`), persiste a versão no `pom.xml` e envia commit e tag `v*` atomicamente para `main`. A publicação exige que a tag pertença a `main` e que `pom.xml` e tag já coincidam; não altera a versão somente no checkout temporário.

O job `release` (`Publish to Maven Central`, com `timeout-minutes: 30`) valida ancestralidade em `main`, higiene pública, chave GPG e versão do POM, executa os testes offline de empacotamento (`tools/release/test_*.py`), roda a suíte unitária e de contrato com o perfil `-P release,release-unit` (`clean verify` com assinatura GPG dos artefatos), constrói e valida o ZIP versionado via `central_bundle.py`, preserva o bundle validado como artifact e realiza um upload único via `publish_central.py --budget-minutes 30 --wait-for accepted` (garantindo o aceite `VALIDATED`/`PUBLISHING` no Central Portal sem prender o runner aguardando propagação assíncrona no CDN). Os recibos e o estado do deployment são preservados em `central-publication-custody-*` mesmo em caso de falha.

O workflow reutilizável `documentation` (`.github/workflows/docs.yml`) roda após o sucesso do job `release`. Commits em `main` ou PRs não iniciam builds automáticos nem publicação de Javadoc. Antes de atualizar projetos consumidores com a nova coordenada, confirme sua disponibilidade pública em `repo.maven.apache.org`. Provas de integração contra bancos gerenciados e topologias de nuvem (Neon, RDS, Azure PostgreSQL, Render etc.) são responsabilidade dos projetos hosts. Consulte [RELEASING.md](RELEASING.md).
