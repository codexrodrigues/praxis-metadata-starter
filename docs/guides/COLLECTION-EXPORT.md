# Exportacao De Colecoes

## Objetivo

Este guia documenta o contrato canonico de backend para exportacao de colecoes
resource-oriented no `praxis-metadata-starter`.

A exportacao existe para atender componentes como Table e List sem mover regras
de seguranca, limites, filtros ou campos autorizados para o frontend.

## Superficie Publica

O endpoint canonico e:

```http
POST /{resource}/export
```

O controller resource-oriented publica esse endpoint quando o service declara
suporte a exportacao de colecao. O resultado pode ser:

- `200 OK`, com arquivo inline no corpo da resposta.
- `202 Accepted`, com `status=deferred`, `downloadUrl` e `jobId` para execucao assincrona.

## Request Canonico

O request de exportacao preserva o estado da colecao:

- `format`: formato solicitado, como `csv` ou `json`.
- `scope`: `auto`, `selected`, `filtered`, `currentPage` ou `all`.
- `selection`: chaves selecionadas, `allMatchingSelected` e exclusoes.
- `fields`: campos solicitados pelo cliente.
- `filters`: DTO de filtro do recurso.
- `sort` e `pagination`: estado de ordenacao e pagina atual.
- `query`: metadados adicionais de busca.
- `includeHeaders`: controla cabecalho em formatos tabulares.
- `applyFormatting`: quando `true`, solicita materializacao de apresentacao governada por campo.
- `maxRows`: limite solicitado pelo cliente.
- `fileName`: nome sugerido para download.
- `formatOptions`: opcoes por formato, como delimitador CSV, encoding, BOM e dialeto de planilha.
- `localization`: locale/timezone usados como contexto de materializacao.

## Responsabilidade Do Recurso

O starter fornece a infraestrutura comum, mas cada recurso continua responsavel por:

- resolver a consulta efetiva;
- aplicar filtros e seguranca do dominio;
- impor limite maximo aceito pelo servidor;
- decidir campos exportaveis;
- decidir se o resultado sera inline ou assincrono;
- devolver metadados de auditoria e truncamento.

Nao trate `maxRows` enviado pelo cliente como limite confiavel. O service deve
calcular o limite efetivo a partir da politica do recurso.

## Capabilities

`GET /{resource}/capabilities` pode publicar detalhes da operacao `export`.
Esses detalhes sao derivados do suporte real do service:

- `formats`: formatos aceitos pelo recurso.
- `scopes`: escopos aceitos pelo recurso.
- `maxRows`: limites por formato.
- `async`: indica se o recurso pode devolver job assincrono.

Se o service nao declara suporte a exportacao, `/capabilities` nao deve publicar
detalhes operacionais de exportacao como se a funcionalidade estivesse disponivel.

## Campos Exportaveis

Campos solicitados pelo cliente devem ser reconciliados com a lista canonica do
recurso. Campos desconhecidos ou nao exportaveis nao podem abrir acesso a dados
internos.

Regra:

- request sem campos usa a lista padrao do recurso;
- request com alguns campos validos exporta apenas os validos;
- request com campos informados, mas nenhum campo suportado, deve falhar com `400`.

Campos exportaveis podem declarar apresentacao serializavel por meio de
`CollectionExportFieldPresentation`. Essa apresentacao inclui semantica como
`currency`, `date`, `boolean`, `format`, `locale`, `currency`, labels booleanos
e `nullDisplay`. A apresentacao enviada pelo cliente deve ser tratada como
intencao materializada e reconciliada contra a allowlist do recurso; ela nao
autoriza campo nem regra de negocio por si so.

## Headers De Resultado

Quando a exportacao e concluida inline, o controller pode publicar headers de
resultado:

- `X-Export-Row-Count`: quantidade de linhas retornadas.
- `X-Export-Truncated`: `true` quando o resultado foi limitado pelo servidor.
- `X-Export-Max-Rows`: limite efetivo aplicado pelo servidor.
- `X-Export-Candidate-Row-Count`: quantidade de linhas candidatas antes do corte.
- `X-Export-Warnings`: avisos legiveis para UI e auditoria.

A UI deve tratar `X-Export-Truncated=true` como resultado parcial e apresentar o
aviso ao operador.

## CSV

O engine CSV canonico serializa linhas tabulares com protecao basica contra
formula injection. Valores iniciados por formula, inclusive depois de whitespace
inicial, sao prefixados com apostrofo antes da serializacao.

Quando `applyFormatting=true`, o CSV usa a apresentacao governada dos campos.
Para compatibilidade com Excel em pt-BR, o cliente pode solicitar
`formatOptions.csv.delimiter=";"`, `encoding="UTF-8"`, `includeBom=true`,
`lineEnding="crlf"` e, quando necessario, `includeSepDirective=true`.
Isso produz CSV compativel com planilha, nao XLSX real.

Essa protecao nao substitui allowlist de campos nem politicas de seguranca do
recurso.

## JSON

O engine JSON canonico preserva a ordem dos campos exportados e emite uma lista
de objetos tabulares.

Quando `applyFormatting=true`, JSON tambem pode materializar apresentacao
governada. Quando `applyFormatting=false`, deve preservar valores crus sempre
que o resolver do recurso os fornecer.

Use JSON quando o consumidor precisar reprocessar dados ou validar o payload de
forma estruturada. Use CSV quando o objetivo principal for abertura em planilhas.

## Excel

`excel` permanece no enum publico para expressar intencao de exportacao XLSX,
mas o recurso so deve anunciar esse formato em `/capabilities` quando houver
engine real registrado. CSV compativel com Excel nao deve ser publicado como
`.xlsx`. Uma engine XLSX futura deve preservar celulas tipadas e estilos de
numero/data/moeda, em vez de serializar tudo como texto.

## Checklist Minima

Antes de publicar exportacao em um recurso:

- `supportsCollectionExport()` retorna `true`.
- `getCollectionExportCapability()` descreve formatos, escopos, limites e async.
- `exportCollection(...)` usa o executor canonico ou engine equivalente governado.
- a consulta aplica filtros, seguranca e limite efetivo do servidor.
- campos exportaveis usam allowlist do recurso.
- campos desconhecidos nao caem silenciosamente para defaults.
- truncamento publica metadados e headers.
- CSV protege contra formula injection.
- teste HTTP cobre sucesso, limite/truncamento e campo rejeitado.
