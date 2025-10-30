# Fase 2 — Controllers e Estratégia de Grupos OpenAPI

Objetivo: garantir uso de `@ApiResource` nos controllers base, validação de conformidade e resolução automática de grupos OpenAPI.

Saídas esperadas:

- Inventário de controllers que estendem `AbstractCrudController` com status de conformidade (@ApiResource configurado)
- Configuração da política `praxis.openapi.validation.api-resource-required` por ambiente (DEV= WARN, PROD= FAIL sugerido)
- Evidências de resolução automática via `/schemas/filtered?path=<base>/all` (quando possível)
- Patches propostos para controllers não conformes

Checklist

- Todo controller que estende `AbstractCrudController` usa `@ApiResource("/api/<contexto>/<recurso>")`
- Política de validação configurada conforme ambiente: `WARN|FAIL|IGNORE`
- (Opcional) `@ApiGroup("<contexto>")` aplicado para agrupamento por domínio
- Removido `getBasePath()` legado (se existir); a detecção é automática

Verificações e evidências

- Buscar controllers base e anotações:
  - `extends AbstractCrudController` e presença de `@ApiResource`
- Validar `/schemas/filtered` (se app em execução):
  - `GET /schemas/filtered?path=/api/<contexto>/<recurso>/all`
  - Confirmar grupo resolvido no log/retorno

Correções comuns

- Substituir `@RestController + @RequestMapping` por `@ApiResource("/api/..." )`
- Definir `@ApiGroup` para criar grupos agregados coerentes no Swagger UI
- Ligar validação obrigatória em PROD (`FAIL`) para impedir regressões

Referências rápidas

- Anotação: src/main/java/org/praxisplatform/uischema/annotation/ApiResource.java:1
- Controller base: src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:242
- Resolução/grupos: docs/technical/ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md:1
- Validação obrigatória @ApiResource: docs/technical/VALIDACAO-API-RESOURCE.md:1
- Controller de schemas: src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:1

Prompt para agente

```
Tarefa: Auditar controllers base e a estratégia de grupos OpenAPI.

Passos:
1) Liste todos os controllers que estendem AbstractCrudController e verifique se usam @ApiResource com path explícito
2) Para os não conformes, proponha patch substituindo @RestController/@RequestMapping por @ApiResource
3) Recomende a política praxis.openapi.validation.api-resource-required (DEV=WARN, PROD=FAIL)
4) Se possível, valide um path com GET /schemas/filtered?path=/api/<contexto>/<recurso>/all e reporte o grupo resolvido

Entregue:
- Tabela/lista de controllers e status de conformidade
- Diffs propostos para cada não conforme
- Evidências de resolução automática ou justificativa se não for possível executar
```

