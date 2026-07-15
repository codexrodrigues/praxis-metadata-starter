# Implementando OptionSourceProvider externo

Use `OptionSourceProvider` quando o contrato publico de uma option source deve
continuar sendo Praxis, mas a execucao vem de uma fonte que nao e o executor JPA
padrao do starter: catalogo externo, query registrada, table function, servico
remoto, banco legado, ou outro mecanismo do host.

O core do Praxis nao deve conhecer SQL, datasource, package, function, bind
parameters, providerConfig, nomes de adapter ou detalhes de sistemas externos.
Esses detalhes pertencem ao provider do host.

## Quando usar PROVIDER_REQUIRED

Use `OptionSourceExecutionMode.PROVIDER_REQUIRED` quando a fonte nao pode ser
executada pelo fallback JPA.

```java
new OptionSourceDescriptor(
    "externalLookup",
    OptionSourceType.LIGHT_LOOKUP,
    ApiPaths.SOME_RESOURCE,
    null,
    null,
    "label",
    "id",
    List.of("companyId"),
    Map.of("companyId", "companyId"),
    new OptionSourcePolicy(false, true, "contains", 3, 10, 20, false, false, "label")
).withExecutionMode(OptionSourceExecutionMode.PROVIDER_REQUIRED);
```

Use `sourceKey` URL-safe, com letras, numeros, ponto, underscore ou hifen. A chave compoe
`/{resource}/option-sources/{sourceKey}/options/*`, portanto barras, espacos e outros caracteres
reservados devem ser modelados como outra chave canonica ou metadata do provider, nao como path.

Se nenhum provider suportar essa fonte e operacao, Praxis retorna erro
controlado de provider ausente. Ele nao tenta resolver paths JPA por acidente.

## Implementando supports, filter e byIds

```java
@Component
public class ExternalLookupOptionSourceProvider implements OptionSourceProvider, Ordered {

    @Override
    public boolean supports(
            OptionSourceDescriptor descriptor,
            OptionSourceExecutionContext context,
            OptionSourceOperation operation
    ) {
        return descriptor != null
                && "externalLookup".equals(descriptor.key())
                && operation == context.operation();
    }

    @Override
    public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
        Object companyId = readFilterValue(request.filterPayload(), "companyId");
        String search = request.search();
        String sortKey = request.sortKey();
        Pageable pageable = request.pageable();

        // Execute the host-specific lookup here.
        return Page.empty(pageable);
    }

    @Override
    public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
        return request.ids().stream()
                .map(String::valueOf)
                .map(id -> new OptionDTO<Object>(id, "Label " + id, Map.of()))
                .toList();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

Use `@Order` ou `Ordered` quando o provider do host deve preceder o fallback JPA.
Se dois providers com a mesma prioridade suportarem a mesma fonte e operacao,
Praxis trata isso como erro de configuracao.

## Sort e Pageable

Praxis valida `request.sortKey()` e `request.pageable().getSort()` antes de
resolver o provider.

Regras importantes:

- se `optionSource.filtering.sortOptions` existir, `sort` precisa ser uma chave
  publicada ali;
- se a fonte nao publicar `sortOptions`, apenas os aliases publicos `id` e
  `label` sao aceitos;
- direcao invalida em `?sort=campo,direcao` retorna `422`;
- provider externo deve preferir `request.sortKey()` para escolher a ordenacao
  host-specific;
- mesmo validado, nenhum valor publico deve ser interpolado diretamente em SQL
  ou linguagem equivalente.

## Filtros e dependencias efetivas

O envelope canonico de filtro e:

```json
{
  "filter": {
    "companyId": "10"
  },
  "filters": [
    { "field": "status", "operator": "equals", "value": "ACTIVE" }
  ],
  "search": "net",
  "sort": "label"
}
```

O provider recebe:

- `request.filterPayload()` com o payload efetivo do filtro do recurso;
- `request.filters()` com filtros estruturados validados contra
  `optionSource.filtering.availableFilters`;
- `request.search()` validado contra `allowSearch` e `minSearchChars`;
- `request.includeIds()` apenas quando a policy permitir;
- `request.ids()` em `byIds`, preservando IDs string e a ordem solicitada.

Em `byIds`, retorne somente `OptionDTO` concretos para IDs encontrados. IDs
inexistentes devem ser omitidos; nao retorne itens `null`. O executor canonico
tambem normaliza a resposta antes do HTTP publico, removendo `null`, descartando
opcoes sem `id` e reordenando os itens pela ordem dos IDs solicitados.

Toda chave de `dependencyFilterMap` precisa existir em `dependsOn`. Quando a
fonte publica dependencias, o provider deve aplicar o payload efetivo
correspondente. Nao publique `dependsOn` ou `dependencyFilterMap` se o provider
ignora a dependencia.

## Contexto privado do host

Substitua `OptionSourceContextResolver` quando o provider precisa de contexto
interno:

```java
@Bean
OptionSourceContextResolver optionSourceContextResolver() {
    return (descriptor, operation) -> new OptionSourceExecutionContext(
            descriptor.key(),
            descriptor.type(),
            descriptor.resourcePath(),
            operation,
            Map.of("tenantId", currentTenant(), "userId", currentUser())
    );
}
```

Esses atributos sao privados. Eles nao podem aparecer em:

- `x-ui.optionSource`;
- OpenAPI;
- `/schemas/filtered`;
- `/schemas/domain`;
- payloads de exemplo;
- mensagens de erro;
- `OptionDTO.extra`.

## Prova publica de catalogos registry-wide

Quando a source corresponde a uma propriedade real, valide o contrato em
`properties.*.x-ui.optionSource` de `/schemas/filtered`. Quando o resource owner
hospeda um catalogo amplo e a source nao corresponde a uma propriedade do DTO ou
do `FilterDTO`, valide-a em:

```http
GET /schemas/domain?resource={resourceKey}
```

Essa projecao deriva do mesmo `OptionSourceRegistry` e publica `filtering`,
`dependsOn`, `dependencyFilterMap`, endpoints e politicas de runtime sem criar
um endpoint de option-source discovery paralelo. A obrigatoriedade de cada
filtro vive em `filtering.availableFilters[].required`.

`OptionSourceExecutionMode.PROVIDER_REQUIRED` governa a selecao interna do
executor e nao deve ser exposto. O consumidor precisa conhecer o contrato e as
capacidades da source, nao qual provider privado o host usou para executa-la.

## Diagnostico de publicacao

Quando o provider possui um catalogo privado de sourceKeys configuradas, publique
tambem um inventario sanitizado para testes ou startup diagnostics:

```java
@Bean
OptionSourcePublicationInventory folhasEmpOptionSourceInventory() {
    return OptionSourcePublicationInventory.of(
        OptionSourcePublicationCandidate.of(
            "administracao-pessoal.folhas-emp.mes-ano-visivel",
            ApiPaths.EMPRESAS,
            "folhas-emp-catalog"
        )
    );
}
```

O bean auto-configurado `OptionSourcePublicationDiagnostics` compara esse
inventario com o `OptionSourceRegistry` publico e aponta:

- `UNPUBLISHED`, quando a sourceKey configurada nao foi publicada por nenhum
  recurso;
- `RESOURCE_MISMATCH`, quando a sourceKey foi publicada por recurso diferente do
  esperado;
- `PUBLISHED`, quando o resource owner canonico publicou o descriptor esperado.

Esse diagnostico nao cria endpoint paralelo e nao executa o provider. Ele existe
para detectar falso progresso: a configuracao privada conhece a fonte, mas o
contrato publico `/{resource}/option-sources/{sourceKey}/options/*` ainda nao
esta publicado. Corrija sempre o `OptionSourceRegistry` do resource owner
canonico.

O inventario deve conter somente identidade publica segura: sourceKey,
resourcePath esperado e uma chave de catalogo nao sensivel. Nao inclua SQL,
HADES, datasource, tenant, usuario, perfil, senha, headers, ROWID, locators,
bind parameters ou nomes internos de adapter.

## Chaves proibidas em contratos publicos

Nao publique detalhes tecnicos como:

- `sql`;
- `provider`;
- `providerName`;
- `providerConfig`;
- `package`;
- `function`;
- `table`;
- `datasource`;
- `context`;
- `hostContext`;
- `attributes`;
- `bindParameters`;
- nomes internos de adapter ou sistema legado.

`OptionDTO.extra` deve conter apenas dados de dominio que a UI pode mostrar ou
usar, como `code`, `status`, `selectable`, `disabledReason` ou atributos
funcionais do catalogo. Nao faca sanitizacao silenciosa no provider; falhe em
teste quando uma chave privada aparecer.

## Dataset version

Se a fonte externa nao depende da tabela JPA do recurso base, sobrescreva:

```java
@Override
public Optional<String> getOptionSourceDatasetVersion(String sourceKey) {
    if ("externalLookup".equals(sourceKey)) {
        return Optional.of("externalLookup:v1");
    }
    return super.getOptionSourceDatasetVersion(sourceKey);
}
```

Isso evita que endpoints de option source consultem o repository JPA do recurso
base apenas para emitir versao.
