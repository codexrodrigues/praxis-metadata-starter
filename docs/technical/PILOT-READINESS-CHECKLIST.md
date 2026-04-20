# Pilot Readiness Checklist

## Status do documento

Checklist de prontidao para abrir o primeiro consumidor externo do
baseline `resource + surfaces + actions + capabilities`.

Ele complementa a documentacao canonica atual do starter.

## Go / No-Go antes do primeiro consumidor externo

Marque todos os itens abaixo antes de abrir a adocao do piloto.

## Baseline do starter

- [ ] Fases 1 a 6 formalmente encerradas no starter
- [ ] piloto interno em `src/test` verde
- [ ] fixture E2E H2 verde no recorte oficial
- [ ] QA independente recente concluido

## Recurso piloto escolhido

- [ ] recurso pequeno e com escopo congelado
- [ ] sem necessidade de contratos paralelos
- [ ] fronteira entre `resource`, `surface` e `action` decidida

## Contrato

- [ ] `@ApiResource(value = ..., resourceKey = ...)` definido
- [ ] DTOs separados de `response`, `create`, `update` e `filter`
- [ ] `@ResourceIntent` usado apenas onde a operacao continua sendo manutencao do recurso
- [ ] `@UiSurface` usado apenas para discovery semantico
- [ ] `@WorkflowAction` usado apenas para comando de negocio explicito

## Discovery

- [ ] `/schemas/filtered` validado
- [ ] `/schemas/catalog` validado
- [ ] `/schemas/surfaces` validado
- [ ] `/schemas/actions` validado
- [ ] `GET /{resource}/capabilities` validado
- [ ] `GET /{resource}/{id}/capabilities` validado
- [ ] detalhes de `export` em `/capabilities` coerentes com `supportsCollectionExport()` e `getCollectionExportCapability()`, quando aplicavel

## Testes e validacao

- [ ] suite focal do host verde
- [ ] validacao de `@Valid` com `400 Validation error`
- [ ] sem payload inline em `surfaces`, `actions` ou `capabilities`
- [ ] exportacao de colecao validada com sucesso, limite/truncamento, headers e rejeicao de campo nao suportado, quando aplicavel

## Operacao

- [ ] rollback definido
- [ ] observabilidade minima definida
- [ ] responsavel tecnico pelo piloto definido
- [ ] limite corporativo de exportacao definido por recurso e comunicado ao consumidor

## Pode ficar fora do primeiro piloto

- `e2e-pg` do starter
- stress mais agressivo de concorrencia/caches lazy
- suite integral do modulo do starter
