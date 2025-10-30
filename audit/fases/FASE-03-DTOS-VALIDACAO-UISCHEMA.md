# Fase 3 — DTOs de Entrada, Bean Validation e @UISchema

Objetivo: falhar cedo (400), refletir regras nos docs e gerar metadados x-ui corretos.

Saídas esperadas:

- Lista de DTOs usados em `create`/`update` com resumo das validações Jakarta
- Confirmação de `@Valid` no `@RequestBody` de `create` e `update`
- Correções propostas para `FieldDataType` legado (substituir `STRING` por `TEXT`), se houver
- (Opcional) Uso de `@UISchema` onde necessário (labels, controlType, opções)

Checklist

- DTOs de entrada anotados com `jakarta.validation` (`@NotBlank`, `@NotNull`, `@Size`, `@Pattern`, `@Email`, `@DecimalMin`, `@Past`, etc.)
- Controllers base mantêm `@Valid` em `create`/`update`
- `FieldDataType` usa valores válidos (ex.: `TEXT`, não `STRING`)
- `@UISchema` é adotada quando ajuda a guiar a UI (sem conflitar com detecção automática)

Verificações e evidências

- Vasculhar DTOs de entrada: campos obrigatórios e regras de formato/faixa
- Confirmar anotações nos métodos do controller:
  - `create(@jakarta.validation.Valid @RequestBody D dto)`
  - `update(@PathVariable ID, @jakarta.validation.Valid @RequestBody D dto)`
- (Opcional) Coletar um schema via `/schemas/filtered` para visualizar `x-ui.validation`

Correções comuns

- Adicionar validações mínimas em campos críticos (nome, documentos, datas)
- Corrigir enum: `FieldDataType.STRING` → `FieldDataType.TEXT`
- Preencher `@UISchema` apenas quando necessário; abusos podem conflitar com detecção automática

Referências rápidas

- Guia de validação no README: README.md:58
- Controller base (assinaturas com @Valid): src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:682
- Enum de tipo: src/main/java/org/praxisplatform/uischema/FieldDataType.java:1
- Resolver de UI e precedência: src/main/java/org/praxisplatform/uischema/extension/CustomOpenApiResolver.java:31

Prompt para agente

```
Tarefa: Auditar DTOs de entrada, validação Jakarta, uso de @Valid e @UISchema.

Passos:
1) Liste os DTOs usados em create/update e levante as validações presentes (ou ausentes)
2) Confirme @Valid em create e update nos controllers base ou específicos
3) Corrija usos de FieldDataType.STRING para TEXT (se existirem)
4) Sugerir @UISchema quando útil para a UI (labels, controlType, etc.)

Entregue:
- Inventário de DTOs, validações e lacunas
- Diffs para: adicionar validações, ajustar FieldDataType, incluir @Valid onde faltar
- (Opcional) Evidência do x-ui.validation via /schemas/filtered
```

