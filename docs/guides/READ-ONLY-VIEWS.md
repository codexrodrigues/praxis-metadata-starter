# Recursos Somente Leitura (Views JPA / @Immutable)

Acelere telas baseadas em views do banco, ou entidades marcadas como `@Immutable`, com o modo read-only canonico do Starter.

- Sem escrever endpoints: herde do controller/servico read-only e ganhe automaticamente filtros, paginacao, opcoes id/label e documentacao.
- Seguranca por design: a superficie read-only canonica nao publica endpoints de escrita.

<a id="endpoints-readonly"></a>
<details>
<summary><strong>Endpoints disponiveis (read-only)</strong></summary>

- `GET /{id}` - busca registro por ID
- `GET /all` - lista completa
- `POST /filter` - paginacao e filtragem via Specifications
- `POST /filter/cursor` - paginacao por cursor
- `POST /locate` - retorna a posicao absoluta de um ID no conjunto filtrado
- `GET /by-ids` - multiplos registros por IDs na ordem solicitada
- `POST /options/filter` - opcoes id/label para selects
- `GET /options/by-ids` - opcoes por IDs com ordem preservada
- `GET /schemas` - redirect para `/schemas/filtered` do recurso

> Nao publicadas: `POST /`, `PUT /{id}`, `DELETE /{id}`, `DELETE /batch`.

</details>

<a id="como-usar"></a>
<details>
<summary><strong>Como usar</strong></summary>

### 1) Entidade (view/@Immutable)

```java
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "vw_vendas_resumo")
public class VendaResumo { /* campos da view */ }
```

### 2) Repository

```java
public interface VendaResumoRepository extends BaseCrudRepository<VendaResumo, Long> { }
```

### 3) Service read-only

```java
@Service
public class VendaResumoService extends AbstractReadOnlyResourceService<
    VendaResumo, VendaResumoDTO, Long, VendaResumoFilterDTO> {

  public VendaResumoService(VendaResumoRepository repo) {
    super(repo, VendaResumo.class);
  }
}
```

### 4) Controller read-only

```java
@ApiResource(value = "/api/relatorios/vendas-resumo", resourceKey = "relatorios.vendas-resumo")
@ApiGroup("relatorios")
public class VendaResumoController extends AbstractReadOnlyResourceController<
    VendaResumoDTO, Long, VendaResumoFilterDTO> {

  @Autowired private VendaResumoService service;

  @Override protected VendaResumoService getService() { return service; }
  @Override protected Long getResponseId(VendaResumoDTO dto) { return dto.getId(); }
}
```

### 5) Filtros e ordenacao

- Use `@Filterable` no DTO de filtro para Specifications
- Defina `@DefaultSortColumn` na entidade para ordenacao padrao

### 6) Semantica de display/read-only

Recursos read-only participam do mesmo contrato canonico de apresentacao:

- o starter publica `x-ui.valuePresentation` quando a intencao de exibicao for inferivel
- isso vale para `currency`, `percentage`, `date`, `datetime`, `time`, `number` e `boolean`
- quando houver necessidade excepcional de override, use `extraProperties` em `@UISchema`

</details>

<a id="beneficios"></a>
<details>
<summary><strong>Beneficios</strong></summary>

- Sem riscos de escrita: o controller nao expoe operacoes mutaveis.
- Endpoints de leitura prontos e documentados em OpenAPI.
- Integracao direta com `/schemas/filtered`, `options/*` e runtime Angular.

</details>

<a id="referencias"></a>
<details>
<summary><strong>Referencias</strong></summary>

- [`AbstractReadOnlyResourceController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractReadOnlyResourceController.html)
- [`AbstractReadOnlyResourceService`](../apidocs/org/praxisplatform/uischema/service/base/AbstractReadOnlyResourceService.html)
- [`@Filterable`](../apidocs/org/praxisplatform/uischema/filter/annotation/Filterable.html)
- [Filtros e Paginacao](FILTROS-E-PAGINACAO.md)

</details>
