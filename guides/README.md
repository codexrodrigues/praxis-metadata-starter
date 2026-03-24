# 📖 Guias de Implementação

Esta seção reúne os guias operacionais do `praxis-metadata-starter`.

Eles foram reorganizados para seguir a hierarquia correta da plataforma:

1. o starter define o contrato canônico
2. o `praxis-api-quickstart` mostra o uso operacional de referência
3. o `praxis-ui-angular` representa a ponta final de consumo

## 📋 Guias Disponíveis

### 🤖 [Guia de CRUD Metadata-Driven por Entidade](GUIA-CLAUDE-AI-CRUD-BULK.md)
Guia principal para agentes de IA gerarem recursos CRUD alinhados ao contrato real do starter.

- cobre DTO, FilterDTO, mapper, repository, service e controller
- ancora exemplos no `praxis-api-quickstart`
- considera o consumo real do `GenericCrudService` no Angular
- nao institucionaliza bulk como baseline

**Ideal para:** gerar um recurso CRUD que publique `x-ui`, `/schemas/filtered`, options e endpoints base consumíveis pela UI.

### 🚀 [Guia para Aplicações Novas](GUIA-CLAUDE-AI-APLICACAO-NOVA.md)
Guia para criar uma nova aplicação Spring Boot mínima e correta sobre o `praxis-metadata-starter`.

- define o baseline mínimo de dependências
- evita prescrever módulos opcionais como obrigatórios
- alinha a aplicação nova com o host de referência e o consumo Angular

**Ideal para:** inicializar um projeto novo com OpenAPI enriquecido, schemas filtrados e primeiro recurso metadata-driven.

### ✅ [Checklist de Validação (IA)](CHECKLIST-VALIDACAO-IA.md)
Lista objetiva para validar builds, grupos OpenAPI, endpoints CRUD, options e schemas após geração por IA.

### 🧪 [Prova Operacional dos Guias de IA](ai-proof/README.md)
Pacote para executar rodadas cegas com LLM, registrar falhas reais e iterar os guias até fechar o protocolo.

---

### 🧭 [CRUD com @ApiResource e @ApiGroup](CRUD-COM-APIRESOURCE.md)
Exponha recursos REST e organize a documentação em grupos OpenAPI.

### 🔎 [Filtros e Paginação](FILTROS-E-PAGINACAO.md)
Implemente filtros com `@Filterable` + Specifications e paginação consistente.

### 🔢 [Ordenação Padrão](ORDEM-PADRAO.md)
Defina `@DefaultSortColumn` e tenha ordenação determinística por padrão.

### ✅ [Options (id/label)](OPTIONS-ENDPOINT.md)
Exponha endpoints de opções id/label usando `@OptionLabel` e `OptionMapper`.

### 📄 [Views / Somente Leitura](READ-ONLY-VIEWS.md)
Aproveite o modo read‑only para entidades de views (`@Immutable`): filtros, paginação e opções prontos, com bloqueio de escrita (405).

### ❗ [Erros e Envelope de Respostas](ERROS-E-RESPOSTAS.md)
Padronize respostas de erro e sucesso para melhor DX/UX.

---

## 🎯 **Como Usar os Guias**

1. **Para primeira implementação:** Comece com a [Visão Geral](../overview/VISAO-GERAL.md) e depois o [Guia de CRUD Metadata-Driven](GUIA-CLAUDE-AI-CRUD-BULK.md)
2. **Para novo projeto:** Use o [Guia de Aplicação Nova](GUIA-CLAUDE-AI-APLICACAO-NOVA.md)
3. **Após geração por IA:** Valide com o [Checklist de Validação](CHECKLIST-VALIDACAO-IA.md)
4. **Para prova operacional dos guias:** Use o pacote [Prova Operacional dos Guias de IA](ai-proof/README.md)
5. **Para exemplos práticos:** Consulte os [Examples](../examples/)
6. **Para detalhes técnicos:** Veja a [Documentação Técnica](../technical/)

---

## 🏆 Critério de Qualidade

Os guias desta seção devem permanecer aderentes a:

- código canônico do `praxis-metadata-starter`
- uso real no `praxis-api-quickstart`
- consumo final no `praxis-ui-angular`

Quando houver divergência entre um guia e esses três pontos, o guia deve ser corrigido.

---

**📌 Nota:** Para uma visão geral de toda a documentação, volte ao [índice principal](../README.md).

---

## 🔎 Referências de API

- Javadoc (publicado no GitHub Pages): [Visão geral](../apidocs/index.html)
- API por pacote: [allpackages-index](../apidocs/allpackages-index.html)

## ✅ Pré‑requisitos
- Java 21
- Spring Boot 3.2+
- SpringDoc OpenAPI (starter já incluso como dependência)
- Maven (para build e publicação)

## 🚀 Exemplo completo (Quickstart)
- Repositório de exemplo: https://github.com/codexrodrigues/praxis-api-quickstart
- Superfície publicada de referência: https://praxis-api-quickstart.onrender.com/
