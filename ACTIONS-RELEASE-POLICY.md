# Actions no fechamento de versões


O padrão durante desenvolvimento é zero execuções remotas. Valide localmente o escopo alterado; commits, PRs, documentação interna e conclusão de tarefas não são motivos para iniciar Actions. Use os workflows manuais somente no fechamento autorizado de uma versão/publicação ou na prova necessária do host já implantado. Não use `[skip ci]` como mecanismo principal nem desabilite checks/proteções para economizar.

Antes de push, tag ou dispatch, confira os gatilhos reais de `.github/workflows/`. Tags de release publicam artefatos: não criá-las para testar a automação. Diagnostique localmente antes de repetir um job; conserve a evidência da revisão e dos artefatos usados. Monitores operacionais explicitamente mantidos são independentes do CI de commits. Consulte [ACTIONS-RELEASE-POLICY.md](ACTIONS-RELEASE-POLICY.md) para os pontos de entrada e recuperação.

## Fluxo deste repositório

`release.yml` é iniciado por dispatch em main com `create_tag=true` e RELEASE_PAT configurado. A preparação agora persiste a versão no POM e envia commit/tag atomicamente. A publicação exige que a tag pertença a main e que POM e tag já coincidam; não corrige a versão somente no checkout temporário.

Uma única sessão Maven executa testes, empacotamento e assinatura antes do goal de publicação. A documentação é um workflow reutilizável dependente do sucesso da release. Commits de main/PR não iniciam build ou Javadoc. Não republique uma versão por demora de propagação: confirme primeiro a disponibilidade do artefato. Consulte RELEASING.md.
