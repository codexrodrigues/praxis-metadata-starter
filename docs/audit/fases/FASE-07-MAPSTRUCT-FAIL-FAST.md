# Fase 7 — MapStruct (Fail‑Fast)

Objetivo: mapeamentos consistentes e detectáveis em build com política fail‑fast.

Saídas esperadas:

- Inventário dos mappers e confirmação de uso da configuração corporativa
- Correções de mapeamento ausente (unmapped target) até build sem erros

Checklist

- Todos os mappers usam: `@Mapper(componentModel = "spring", config = CorporateMapperConfig.class)`
- Build falha quando há campos não mapeados (policy `ERROR`)
- Ajustes de `@Mapping` para campos necessários (aninhados quando aplicável)

Verificações e evidências

- Listar interfaces `@Mapper` e conferir annotation/config
- Rodar build e capturar erros de unmapped

Correções comuns

- Adicionar `@Mapping` explícito para campos
- Criar mappers auxiliares para propriedades complexas

Referências rápidas

- Configuração corporativa: src/main/java/org/praxisplatform/uischema/mapper/config/CorporateMapperConfig.java:1
- README (seção MapStruct): README.md:42

Prompt para agente

```
Tarefa: Auditar os mappers MapStruct e aplicar fail-fast.

Passos:
1) Inventarie os mappers e verifique se usam CorporateMapperConfig (componentModel="spring", unmappedTargetPolicy=ERROR)
2) Compile e corrija unmapped (adicione @Mapping) até sucesso

Entregue:
- Lista de mappers e gaps
- Diffs de correção
- Resultado de build final
```

