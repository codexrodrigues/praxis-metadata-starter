# Enterprise UI Adaptation Lab — boundary Metadata

Status: `PLANEJAMENTO_REVISADO / SEM_NOVO_CONTRATO` (2026-09-05).
Alteração documental; arquitetura futura transversal.

A [fundação canônica de workspace no Config](https://github.com/codexrodrigues/praxis-config-starter/blob/85f55e608237d3389d1e2b908fd478a355c794cb/docs/ui-layout-workspace-foundation.md)
documenta a separação entre baseline, autoria e composição autorizada.

## Responsabilidade canônica

Metadata continua dono de resource/operation identity, schemas, x-ui, actions,
surfaces, capabilities, links, significado de campo e fatos de disponibilidade.
Domínio/segurança do host autoriza as operações e os dados.

Metadata não possui rota Angular, grafo de página, identidade de instância
visual, audiência, preferências, revisão de layout ou publicação.

`resourceKey` e `surfaceRef` vinculam domínio à UI, mas não substituem
`pageKey` e identidade da instância: duas tabelas podem consumir o mesmo
recurso na mesma página. Uma página pode compor várias surfaces.

## Baseline autorada e projeção autorizada são distintas

Fluxo conceitual a provar antes de uma SPI nova:

```text
baseline autorada/versionada da página e dos componentes
  + refs de recurso/schema/surface publicados por Metadata
  + política atual do principal/contexto autorizados
  -> projeção de elementos e operações permitidos
  -> adaptação de apresentação validada no Config
  -> snapshot/diagnostics que continuam respeitando a política
```

A baseline de produto pode ser template ou publicação de composição já
existente. Sua ownership não deve ser transferida para Metadata só porque
parte do conteúdo vem de schema. Inventariar as projeções atuais primeiro.

O fingerprint da base autorada e o fingerprint da projeção autorizada têm
finalidades distintas. Hash de schema não é, sozinho, hash de contexto ou ETag
da apresentação efetiva. Evitar IDs sensíveis em hashes expostos/diagnostics.

## Invariantes e provas

- Propriedade negada não chega ao cliente por baseline, snapshot, editor,
  explain ou schema. Ocultar o controle não protege dados já enviados.
- Permissão de autoria/publicação não concede leitura/escrita do domínio.
- Config não habilita ação negada nem afrouxa uma restrição de campo.
- Mesma identidade de recurso/surface permanece coerente em OpenAPI,
  filtered schema, capabilities e vínculo da apresentação.
- Remover/renomear campo referenciado gera diagnóstico de compatibilidade;
  não fabricar alias ou selector a partir de label/índice.
- Atualizar baseline preserva deltas compatíveis; uma revisão incompatível
  não pode ser aplicada parcialmente e apresentada como publicação válida.

Fixtures mínimas: duas instâncias do mesmo recurso, Table/Form numa página
com tabs, campo permitido/negado por principal, upgrade aditivo, remoção de
selector e tentativa de executar action negada. Usar domínio sintético no
Quickstart; nada exige HADES ou cadastro Ergon.

Somente uma lacuna comprovada nessas fixtures autoriza proposta de extensão
pública Metadata. Resolver a composição de versões local antes de atribuir
resultado do Quickstart ao checkout Metadata atual.
