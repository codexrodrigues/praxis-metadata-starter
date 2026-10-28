# Recursos Somente Leitura (Views JPA / @Immutable)

Acelere telas baseadas em views do banco, relatorios analiticos ou entidades marcadas como `@Immutable` com o modo read-only canonico do Starter.

- **Sem escrever endpoints manuais**: herde de `AbstractReadOnlyResourceController` e `AbstractReadOnlyResourceService` e ganhe automaticamente filtros dinamicos, paginacao offset/cursor, locate, opcoes `id/label`, estatisticas (`/stats/*`), `/capabilities`, `/surfaces` e `/schemas/filtered`.
- **Seguranca por design**: a superficie read-only canonica nao publica endpoints de escrita (`POST /`, `PUT /{id}`, `DELETE /{id}`, `DELETE /batch`).

<a id="endpoints-readonly"></a>
<details open>
<summary><strong>Endpoints disponiveis (read-only)</strong></summary>

- `GET /{id}` - busca registro por ID (com HATEOAS `_links` e `ETag`)
- `GET /all` - lista completa ordenada por `@DefaultSortColumn`
- `POST /filter` - paginacao e filtragem via `GenericSpecificationsBuilder`
- `POST /filter/cursor` - paginacao keyset por cursor (`after`/`before`)
- `POST /locate` - retorna a posicao absoluta e pagina de um ID no conjunto filtrado
- `GET /by-ids` - multiplos registros por IDs na ordem solicitada
- `POST /options/filter` - opcoes `OptionDTO{id,label}` paginadas para selects/autocompletes
- `GET /options/by-ids` - reidratacao de opcoes por IDs com ordem preservada
- `POST /stats/group-by`, `POST /stats/timeseries`, `POST /stats/distribution`, `POST /stats/comparison` - agregacoes analiticas automaticas quando `StatsFieldRegistry` + `StatsSupportMode.AUTO` estao ativos no service
- `GET /capabilities` e `GET /{id}/capabilities` - snapshot agregado de operacoes, surfaces, actions e stats/export
- `GET /schemas` - redirect para `/schemas/filtered` do recurso

> Nao publicadas: `POST /`, `PUT /{id}`, `DELETE /{id}`, `DELETE /batch`.

</details>

<a id="como-usar"></a>
<details open>
<summary><strong>Como usar (Compila de Primeira)</strong></summary>

### Assinaturas Genericas Read-Only

| Camada | Tipo Base | Ordem dos Parametros Genericos |
| --- | --- | --- |
| **Repository** | `BaseCrudRepository` | `<E, ID>` *(2 parametros)* |
| **Mapper** | `ResourceMapper` | `<E, ResponseDTO, Void, Void, ID>` *(5 parametros — use `Void` em `CreateDTO` e `UpdateDTO`)* |
| **Service** | `AbstractReadOnlyResourceService` | `<E, ResponseDTO, ID, FilterDTO>` *(4 parametros — `E` e o 1º, `ID` e o 3º)* |
| **Controller** | `AbstractReadOnlyResourceController` | `<ResponseDTO, ID, FilterDTO>` *(3 parametros — **sem `E`**, `ID` e o 2º)* |

### 1) Entidade (view / `@Immutable`)

```java
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;
import org.praxisplatform.uischema.annotation.OptionLabel;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;

@Entity
@Immutable
@Table(name = "vw_vendas_resumo")
public class VendaResumo {

    @Id
    private Long id;

    @OptionLabel
    @Column(nullable = false)
    private String regional;

    @Column(nullable = false)
    private String categoria;

    @DefaultSortColumn(priority = 1, ascending = false)
    @Column(name = "data_competencia", nullable = false)
    private LocalDate dataCompetencia;

    @Column(name = "valor_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal valorTotal;

    // getters e setters
}
```

### 2) Repository

```java
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;

public interface VendaResumoRepository extends BaseCrudRepository<VendaResumo, Long> {
}
```

### 3) Mapper Read-Only (`CorporateMapperConfig`)

```java
import org.mapstruct.Mapper;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.mapper.config.CorporateMapperConfig;

@Mapper(config = CorporateMapperConfig.class)
public interface VendaResumoMapper extends ResourceMapper<
        VendaResumo,
        VendaResumoDTO,
        Void,
        Void,
        Long> {

    @Override
    default VendaResumo newEntity(Void dto) {
        throw new UnsupportedOperationException("Read-only resource");
    }

    @Override
    default void applyUpdate(VendaResumo entity, Void dto) {
        throw new UnsupportedOperationException("Read-only resource");
    }

    @Override
    default Long extractId(VendaResumo entity) {
        return entity.getId();
    }
}
```

### 4) Service Read-Only (com `StatsFieldRegistry` + `StatsSupportMode.AUTO`)

```java
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.springframework.stereotype.Service;

@Service
public class VendaResumoService extends AbstractReadOnlyResourceService<
        VendaResumo,
        VendaResumoDTO,
        Long,
        VendaResumoFilterDTO> {

    private final VendaResumoMapper mapper;
    private final StatsFieldRegistry statsFieldRegistry = StatsFieldRegistry.builder()
            .categoricalGroupByBucket("regional", "regional")
            .categoricalGroupByBucket("categoria", "categoria")
            .temporalTimeSeriesField("dataCompetencia", "dataCompetencia")
            .numericDistributionField("valorTotal", "valorTotal")
            .numericMeasureField("valorTotal", "valorTotal")
            .build();

    public VendaResumoService(VendaResumoRepository repo, VendaResumoMapper mapper) {
        super(repo, VendaResumo.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<VendaResumo, VendaResumoDTO, ?, ?, Long> getMapper() {
        return mapper;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return statsFieldRegistry;
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getComparisonStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }
}
```

### 5) Controller Read-Only

```java
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;

@ApiResource(value = "/api/relatorios/vendas-resumo", resourceKey = "relatorios.vendas-resumo")
@ResourceIdentity(
        idField = "id",
        labelField = "regional",
        subtitleField = "categoria",
        badgeField = "categoria"
)
@QuickFilter(fields = {"regional", "categoria"}, minLength = 2)
@ApiGroup("relatorios")
public class VendaResumoController extends AbstractReadOnlyResourceController<
        VendaResumoDTO,
        Long,
        VendaResumoFilterDTO> {

    private final VendaResumoService service;

    public VendaResumoController(VendaResumoService service) {
        this.service = service;
    }

    @Override
    protected BaseResourceQueryService<VendaResumoDTO, Long, VendaResumoFilterDTO> getService() {
        return service;
    }

    @Override
    protected Long getResponseId(VendaResumoDTO dto) {
        return dto.getId();
    }
}
```

### 6) Filtros, ordenacao e semantica de display

- Use `@Filterable` no `FilterDTO` (`LIKE`, `EQUAL`, `IN`, `BETWEEN`, `IN_LAST_DAYS`) para `GenericSpecificationsBuilder`.
- Defina `@DefaultSortColumn` na entidade para ordenacao padrao e `@OptionLabel` para projecao automatica de `OptionDTO`.
- O starter publica `x-ui.valuePresentation` automaticamente para `currency` (`UISchemaPreset.MONEY` / `NumericFormat.CURRENCY`), `percentage`, `date`, `datetime`, `time`, `number` e `boolean`.

</details>

<a id="beneficios"></a>
<details open>
<summary><strong>Beneficios</strong></summary>

- **Sem riscos de escrita**: o controller nao expoe operacoes mutaveis.
- **Analytics nativo**: `/stats/group-by`, `/stats/timeseries`, `/stats/distribution` e `/stats/comparison` prontos para `@praxisui/charts` e tabelas analiticas.
- **Integracao direta** com `/schemas/filtered`, `/capabilities`, `options/*` e runtime Angular.

</details>

<a id="referencias"></a>
<details>
<summary><strong>Referencias</strong></summary>

- [`AbstractReadOnlyResourceController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractReadOnlyResourceController.html)
- [`AbstractReadOnlyResourceService`](../apidocs/org/praxisplatform/uischema/service/base/AbstractReadOnlyResourceService.html)
- [`@Filterable`](../apidocs/org/praxisplatform/uischema/filter/annotation/Filterable.html)
- [Filtros e Paginacao](FILTROS-E-PAGINACAO.md)

</details>
