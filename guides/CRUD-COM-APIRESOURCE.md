# Uso de @ApiResource no Core Legado

Este guia foi mantido por compatibilidade para times que ainda operam no core
legado baseado em `AbstractCrudController`.

Para aplicacoes novas, nao use este guia como baseline. Comece por:

- `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`

## Quando este guia ainda faz sentido

Use este material apenas se o codigo existente:

- ainda herda de `AbstractCrudController`
- ainda depende de `BaseCrudService`
- ainda esta em migracao para o core resource-oriented

## Papel de @ApiResource no legado

Mesmo no core legado, `@ApiResource` continua importante para:

- declarar o base path
- alinhar a documentacao OpenAPI
- gerar links HATEOAS coerentes
- permitir resolucao automatica de grupos

Exemplo legado:

```java
@ApiResource("/api/human-resources/funcionarios")
@ApiGroup("human-resources")
public class FuncionarioController extends AbstractCrudController<Funcionario, FuncionarioDTO, Long, FuncionarioFilterDTO> {
    // wiring legado
}
```

## Recomendacao de plataforma

Nao evolua um projeto novo em cima deste guia.

Se o sistema ainda estiver no core legado, a direcao correta de plataforma e:

1. remover DTO unico
2. migrar para controllers e services resource-oriented
3. explicitar `resourceKey`
4. publicar discovery semantico com surfaces, actions e capabilities onde fizer sentido

## Referencias

- `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `../technical/VALIDACAO-API-RESOURCE.md`
