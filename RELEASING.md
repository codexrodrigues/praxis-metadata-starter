# Releasing — praxis-metadata-starter

Este documento descreve como publicar um Release Candidate (RC) e versões finais no Maven Central usando os workflows deste repositório.

## Pré‑requisitos
- GitHub Secrets (no repositório):
  - `CENTRAL_TOKEN_USER` e `CENTRAL_TOKEN_PASS` (tokens do Sonatype Central Portal)
  - `GPG_PRIVATE_KEY` (chave privada ASCII‑armored ou base64, sem CRLF)
  - `GPG_PASSPHRASE` (passphrase da chave)
  - `GPG_KEY_ID` (opcional; se ausente, o workflow resolve automaticamente)
- Java 21 instalado localmente (para builds locais).

## Fluxo (Release Candidate)
1) Opcional — validar localmente (sem assinar):
```
./mvnw -B -DskipTests -T 1C clean verify
./mvnw -B javadoc:javadoc && test -d target/site/apidocs
```
2) Criar a tag do RC e enviar:
```
git tag v1.0.0-rc.6
git push origin v1.0.0-rc.6
```
3) Acompanhar o workflow “Release Java Starter (praxis-metadata-starter)”
- O workflow resolve a versão a partir da tag (`v` é removido → `1.0.0-rc.6`).
- Passos: importar GPG → `versions:set` → `clean verify` com perfil `release` (assina) → publicar via Central Plugin.

4) Verificar artefatos assinados no job:
- `target/praxis-metadata-starter-1.0.0-rc.6.jar(.asc)`
- `*-sources.jar(.asc)` e `*-javadoc.jar(.asc)`

5) Acompanhar aprovação no Sonatype Central Portal
- O Central Publishing geralmente finaliza em minutos.

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
- Assinaturas ausentes:
  - Confirme execução com `-P release -Dgpg.skip=false`; o job usa isso por padrão.
