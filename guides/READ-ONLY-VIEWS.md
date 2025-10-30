# Recursos Somente Leitura (Views JPA / @Immutable)

Acelere telas baseadas em Views do banco (ou entidades marcadas como `@Immutable`) com o modo read‑only do Starter.

- Sem escrever endpoints: herde do controller/serviço read‑only e ganhe automaticamente filtros, paginação, opções id/label e documentação.
- Segurança por padrão: operações de escrita (POST/PUT/DELETE) respondem `405 Method Not Allowed`.

## Endpoints disponíveis (read‑only)

- `GET /{id}` — busca registro por ID
- `GET /all` — lista completa (aplica ordenação padrão se configurada)
- `POST /filter` — paginação/filtragem via Specifications
- `POST /filter/cursor` — paginação por cursor (keyset)
- `POST /locate` — retorna posição absoluta e página de um ID com base no filtro/sort
- `GET /by-ids` — múltiplos registros por IDs na ordem solicitada
- `POST /options/filter` — opções id/label para selects (paginadas)
- `GET /options/by-ids` — opções por IDs (ordem preservada)
- `GET /schemas` — redirect para `/schemas/filtered` do recurso

> Bloqueadas: `POST /`, `PUT /{id}`, `DELETE /{id}`, `DELETE /batch` → `405`.

## Como usar

### 1) Entidade (View/@Immutable)

```java
import org.hibernate.annotations.Immutable;

@Entity @Immutable
@Table(name = "vw_vendas_resumo")
public class VendaResumo { /* campos da view */ }
```

### 2) Repository
```java
public interface VendaResumoRepository extends BaseCrudRepository<VendaResumo, Long> { }
```

### 3) Service read‑only
```java
@Service
public class VendaResumoService extends AbstractReadOnlyService<VendaResumo, VendaResumoDTO, Long, VendaResumoFilterDTO> {
  public VendaResumoService(VendaResumoRepository repo) { super(repo, VendaResumo.class); }
}
```

### 4) Controller read‑only
```java
@ApiResource("/api/relatorios/vendas-resumo")
@ApiGroup("relatorios")
@RestController
public class VendaResumoController extends AbstractReadOnlyController<
    VendaResumo, VendaResumoDTO, Long, VendaResumoFilterDTO> {

  @Autowired private VendaResumoService service;
  @Override protected BaseCrudService<VendaResumo, VendaResumoDTO, Long, VendaResumoFilterDTO> getService() { return service; }
  @Override protected Long getEntityId(VendaResumo e) { return e.getId(); }
  @Override protected Long getDtoId(VendaResumoDTO d) { return d.getId(); }
  @Override protected VendaResumoDTO toDto(VendaResumo e) { /* map */ }
  @Override protected VendaResumo toEntity(VendaResumoDTO d) { /* map */ }
}
```

### 5) Filtros e ordenação
- Use `@Filterable` no DTO de filtro para Specifications (26 operações)
- Defina `@DefaultSortColumn` na entidade para ordenação padrão

## Benefícios (prontos para produção)
- Sem riscos de escrita: 405 para operações mutáveis.
- 9+ endpoints de leitura prontos e documentados (Swagger/OpenAPI) com cache e grupos por path.
- Integração com UI: `options` e `/schemas/filtered` aceleram formulários/tabelas read‑only.
- Consistência corporativa: respostas padronizadas (`RestApiResponse`) e HATEOAS opcional.

## Referências
- [`AbstractReadOnlyController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractReadOnlyController.html)
- [`AbstractReadOnlyService`](../apidocs/org/praxisplatform/uischema/service/base/AbstractReadOnlyService.html)
- [`@Filterable`](../apidocs/org/praxisplatform/uischema/filter/annotation/Filterable.html)
- [Filtros e Paginação](FILTROS-E-PAGINACAO.md)

