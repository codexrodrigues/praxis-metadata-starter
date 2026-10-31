# Configuration-driven Development

## Definição

**Configuration-driven Development** permite adaptar comportamento, apresentação, layouts e regras operacionais por ambiente, tenant ou perfil de usuário por meio de configurações governadas e versionadas, sem recompilar o código-fonte do domínio base.

## Como a plataforma Praxis separa Código vs Configuração

Na arquitetura da **Praxis Platform** — em que recursos, regras, ações, dados e políticas são descritos uma vez para serem operados por interfaces tradicionais, interfaces generativas, agentes de IA e integrações —, existe uma fronteira canônica estrita entre o que nasce no contrato do recurso e o que é governado e configurado em runtime:

| Camada | Módulo Canônico | O que governa |
| :--- | :--- | :--- |
| **Contrato base e semântica do recurso** | `praxis-metadata-starter` | Recursos (`@ApiResource`), DTOs, `@Schema`, `@UISchema`, `@Filterable`, `@UiSurface`, `@WorkflowAction`, `/schemas/filtered`, `/schemas/domain`, capabilities e propriedades `praxis.*` do Spring Boot. |
| **Configuração persistida, personalização e IA governada** | `praxis-config-starter` | Estado persistido por tenant/usuário/ambiente (`ui_user_config` com ETag forte), catálogo de componentes (`ai_registry`), ingestão de catálogo (`api_metadata`), regras de domínio (`domain-rules`) e fluxos de authoring assistido por IA em `/api/praxis/config/**`. |

Essa separação garante que:
1. O host Spring Boot funcione imediatamente apenas com `praxis-metadata-starter` (zero configuração persistida obrigatória).
2. Quando `praxis-config-starter` está presente, personalizações de layout, variantes por tenant e decisões governadas por IA se sobreponham de forma auditável sobre a base semântica publicada pelo metadata starter.

## Referências internas

- [Visão geral da arquitetura](../architecture-overview.md)
- [Conceito: `@UISchema` e metadados `x-ui`](ui-schema.md)

## Referências públicas

- [The Twelve-Factor App — Config](https://12factor.net/config)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
