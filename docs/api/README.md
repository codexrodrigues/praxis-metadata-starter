# 📡 Documentação de API

Esta seção está reservada para documentação de API detalhada e especificações técnicas dos endpoints.

## 🚧 **Em Construção**

Esta seção será expandida para incluir:

- 📋 **Especificações OpenAPI** completas
- 🔌 **Referência de Endpoints** detalhada  
- 📊 **Esquemas de Dados** e modelos
- 🔒 **Documentação de Segurança**
- ⚡ **Exemplos de Request/Response**

---

## 🧭 Versão de Dados para Cache

Endpoints de listagem retornam, quando disponível, o header `X-Data-Version`
informando a versão atual do dataset. Interfaces corporativas podem usar este
valor para invalidar caches ao detectar alterações nos dados sem refazer
requisições pesadas.

```java
// Exemplo de implementação no serviço
@Override
public Optional<String> getDatasetVersion() {
    return repository.maxUpdatedAt().map(Instant::toString);
}
```

O cabeçalho é anexado automaticamente nas respostas dos endpoints:
- `POST /{resource}/filter`
- `POST /{resource}/filter/cursor`
- `GET  /{resource}/all`
- `GET  /{resource}/by-ids`
- `POST /{resource}/options/filter`
- `GET  /{resource}/options/by-ids`

Quando o serviço não sobrescreve `getDatasetVersion()`, o cabeçalho não é
enviado, preservando a compatibilidade com implementações existentes.

---

## 📑 Ordenação e Paginação

Endpoints paginados aceitam os parâmetros `page`, `size` e `sort`. O parâmetro
`sort` pode ser repetido no formato `campo,asc` ou `campo,desc`. Entradas em
branco são ignoradas e direções inválidas assumem `asc`. Quando não informado,
a ordenação definida por `@DefaultSortColumn` na entidade é aplicada
automaticamente, suportando múltiplas colunas.

O tamanho máximo de página é configurável via `praxis.pagination.max-size`
(padrão: 200). Requisições que excederem esse valor recebem `422
Unprocessable Entity`.

```
POST /api/funcionarios/filter?page=0&size=10&sort=departamento,asc&sort=nome,desc
```

Utilitários `SortBuilder` e `PageableBuilder` garantem a composição consistente
do `Pageable` com ordenação de fallback, evitando divergências em cenários
corporativos.

---

## 🔄 Recuperação em Lote por IDs

### `GET /{resource}/by-ids`

Endpoint que retorna múltiplos registros de uma só vez, mantendo a ordem dos
IDs fornecidos. Útil para interfaces corporativas que precisam pré-carregar
registros selecionados.

- **Parâmetro**: `ids` (repetível) – lista de identificadores.
- **Limite**: configurável via `praxis.query.by-ids.max` (padrão: 200).

**Exemplo:**

```
GET /api/funcionarios/by-ids?ids=1&ids=3&ids=2
→ [ {"id":1}, {"id":3}, {"id":2} ]
```

---

## ⚡ Injeção de IDs no Filtro

### `POST /{resource}/filter?includeIds=...`

Garante que registros específicos apareçam no topo da **primeira** página de resultados.

- **Parâmetro**: `includeIds` (repetível) – lista de IDs a serem injetados.
- **Comportamento**:
  - IDs ausentes na primeira página são buscados e adicionados no topo.
  - Repetir o parâmetro nas páginas subsequentes evita que os mesmos registros reapareçam,
    porém sem nova injeção.
  - IDs duplicados são ignorados, considerando apenas a primeira ocorrência.
  - `totalElements` permanece inalterado.

**Exemplo:**

```
POST /api/funcionarios/filter?includeIds=5&includeIds=1
{}
→ página 0: data.content[0].id = 5, data.content[1].id = 1
→ página 1: includeIds=5&includeIds=1 → itens 5 e 1 não reaparecem
```

---

## 📍 Localização de Registros

### `POST /{resource}/locate`

Descobre a posição absoluta e a página de um ID considerando os critérios de
filtro e ordenação.

- **Query Params**:
  - `id` – identificador do registro alvo
  - `size` – tamanho da página (limitado por `praxis.pagination.max-size`)
  - `sort` – ordenação (repetível), ex.: `nome,asc`
- **Corpo**: DTO de filtro
- **Retorno**: `{ "position": 42, "page": 4 }`
- **Sem suporte**: serviços que não implementarem retornam `501 Not Implemented`

---

## 🔀 Paginação por Cursor

### `POST /{resource}/filter/cursor`

Oferece paginação estável para listas longas utilizando keyset pagination. Os
cursores são codificados em Base64 URL-safe e retornam nos campos `next` e
`prev`.

- **Parâmetros**:
  - `after` – cursor para avançar na lista
  - `before` – cursor para retroceder
  - `size` – quantidade de registros (limitado por `praxis.pagination.max-size`)
  - `sort` – ordenação estável, ex.: `updatedAt,desc` e `id,desc`
- **Comportamento**: serviços que não implementarem retornam `501 Not Implemented`
  com a mensagem "não implementado".

---

## 🎯 Projeção Leve para Selects

### `POST /{resource}/options/filter`

Retorna uma página com objetos `{id, label, extra}` usando `OptionMapper`,
ideal para popular componentes de seleção.

Respeita o limite configurável `praxis.pagination.max-size` para o tamanho da página.

```http
POST /api/funcionarios/options/filter
{}
→ { "content": [ { "id":1, "label":"João" } ], ... }
```

### `GET /{resource}/options/by-ids`

Busca opções específicas pelos IDs informados, preservando a ordem de
entrada e respeitando o limite `praxis.query.by-ids.max`.

```http
GET /api/funcionarios/options/by-ids?ids=1&ids=3
→ [ {"id":1, "label":"João"}, {"id":3, "label":"Maria"} ]
```

---

## 📚 **Recursos Relacionados**

Enquanto esta seção está em desenvolvimento, consulte:

- 📖 [Guias de Implementação](../guides/) - Para implementação prática
- 💡 [Exemplos Práticos](../examples/) - Para casos de uso reais
- 🔧 [Documentação Técnica](../technical/) - Para detalhes internos
- 🏠 [Índice Principal](../README.md) - Para visão geral completa

---

**📝 Contribuições são bem-vindas para expandir esta seção!**