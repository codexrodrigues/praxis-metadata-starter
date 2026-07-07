# Releasing — praxis-metadata-starter

Este documento descreve como publicar um Release Candidate (RC) e versões finais no Maven Central usando os workflows deste repositório.

## Pré‑requisitos
- GitHub Secrets (no repositório):
  - `CENTRAL_TOKEN_USER` e `CENTRAL_TOKEN_PASS` (tokens do Sonatype Central Portal)
  - `GPG_PRIVATE_KEY` (chave privada ASCII‑armored ou base64, sem CRLF)
  - `GPG_PASSPHRASE` (passphrase da chave)
  - `GPG_KEY_ID` (opcional; se ausente, o workflow resolve automaticamente)
  - `RELEASE_PAT` (recomendado quando o workflow `workflow_dispatch` criar tags; pushes feitos com `GITHUB_TOKEN` nao disparam novo workflow de tag)
- Java 21 instalado localmente (para builds locais).

## Fluxo (Release Candidate)
1) Opcional — validar localmente (sem assinar):
```
./mvnw -B -DskipTests -T 1C clean verify
./mvnw -B javadoc:javadoc && test -d target/site/apidocs
```
2) Para qualquer mudanca de contrato publico, executar o gate corporativo antes
   da tag:
```
scripts/check-public-contract-gate.sh --base origin/main
```

Esse gate e obrigatorio quando a mudanca toca `x-ui`, `/schemas/filtered`,
`/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities`,
anotacoes publicas, enriquecimento OpenAPI, headers, ETag, `X-Schema-Hash` ou
controladores/base publicos.

Checklist minima para esse caso:
- usar nova tag/versao do starter ou instalar localmente o artefato alterado de
  forma controlada antes de validar consumidores;
- validar `praxis-api-quickstart` contra exatamente esse artefato;
- revisar docs/examples que espelham o contrato publico;
- registrar comandos executados e escopo nao validado;
- garantir que `target/**`, `.flattened-pom.xml`, `.m2repo/` e artefatos
  gerados de release nao entram no change set.

3) Criar a tag do RC e enviar:
```
git tag v1.0.0-rc.6
git push origin v1.0.0-rc.6
```

Para publicar as mudancas atuais da plataforma, apos o merge em `main`, use a proxima coordenada nao publicada:
```
git tag v8.0.0-rc.N
git push origin v8.0.0-rc.N
```
4) Acompanhar o workflow “Release Java Starter (praxis-metadata-starter)”
- O workflow resolve a versão a partir da tag (`v` é removido → `1.0.0-rc.6`).
- Passos: importar GPG → `versions:set` → `clean verify` com perfil `release` (assina) → publicar via Central Plugin.
- O passo `Publish to Central` aguarda até o upload ser aceito pelo Central
  Portal. Em seguida, o passo `Verify Maven Central availability` tenta resolver
  o POM em `repo1.maven.org` e executa `mvn dependency:get` para confirmar que a
  versão já pode ser consumida por hosts como o `praxis-api-quickstart`.
- Atualize consumidores apenas depois que `Verify Maven Central availability`
  terminar com sucesso. Se essa etapa falhar por propagação lenta, a publicação
  pode ter sido enviada ao Central Portal, mas ainda não deve ser tratada como
  disponível para consumo.

5) Verificar artefatos assinados no job:
- `target/praxis-metadata-starter-1.0.0-rc.6.jar(.asc)`
- `*-sources.jar(.asc)` e `*-javadoc.jar(.asc)`

6) Acompanhar aprovação no Sonatype Central Portal
- O Central Publishing geralmente finaliza em minutos.
- Quando a publicação/propagação demorar, use o log periódico do passo
  `Verify Maven Central availability` para distinguir fila/propagação de uma
  versão já consumível pelo Maven Central público.

## Fluxo (Versão Final)
- Mesmo processo, usando tag sem sufixo RC, por exemplo:
```
git tag v1.0.0
git push origin v1.0.0
```

## Observações
- O `pom.xml` no repositório mantém a versão “base”. O workflow usa `versions:set` apenas dentro da execução do CI, sem commit.
- O flatten POM no perfil `release` remove o parent e gera um POM compatível com Central.
- Para publicar a documentação (Javadoc HTML + markdown convertido para HTML):
  - Faça push para `main` ou crie uma tag `v*` e veja o workflow “Documentation”.

## Troubleshooting
- GPG key id não resolvido:
  - Garanta que `GPG_PRIVATE_KEY` está sem BOM/CRLF. O workflow já sanitiza; ver logs da etapa “Import GPG private key”.
- Falha na publicação (no goal `publish`):
  - Verifique `CENTRAL_TOKEN_USER/PASS` e se o server `central` foi injetado pelo `actions/setup-java` (logs).
- `Verify Maven Central availability` falhou:
  - O artefato não respondeu HTTP 200 em `repo1.maven.org` dentro da janela do
    workflow. Não atualize consumidores ainda.
  - Confira o Central Portal e reexecute apenas a verificação quando houver sinal
    de publicação concluída; se a versão continuar ausente, trate como falha de
    release.
- Assinaturas ausentes:
  - Confirme execução com `-P release -Dgpg.skip=false`; o job usa isso por padrão.
