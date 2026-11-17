package org.praxisplatform.uischema.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-anotação canônica para declarar um recurso REST da plataforma.
 *
 * <p>
 * {@code @ApiResource} combina {@link RestController} e {@link RequestMapping} e passa a ser a
 * fonte de verdade do recurso em três eixos complementares:
 * </p>
 *
 * <ul>
 *   <li>path REST do recurso, publicado no OpenAPI e usado pelos controllers canônicos;</li>
 *   <li>identidade semântica estável via {@link #resourceKey()}, usada por discovery e herdada por {@code @ResourceFact};</li>
 *   <li>metadados estruturais para scanner OpenAPI, HATEOAS, {@link QuickFilter}, {@link ResourceIdentity} e schemas filtrados.</li>
 * </ul>
 *
 * <p>
 * A distinção entre path e {@code resourceKey} é intencional:
 * </p>
 *
 * <ul>
 *   <li>o path diz onde o recurso vive operacionalmente, por exemplo {@code /api/human-resources/employees};</li>
 *   <li>o {@code resourceKey} diz qual recurso a plataforma está tratando semanticamente, por exemplo
 *   {@code human-resources.employees}.</li>
 * </ul>
 *
 * <p>
 * No baseline atual, o {@code resourceKey} é a chave usada para catálogos de surfaces/actions,
 * snapshots de capabilities e availability contextual. Por isso ele não deve variar apenas porque
 * a URL operacional do recurso mudou. Quando combinada com {@link UiAnalytics} em nível de classe,
 * a projeção analítica anexa automaticamente {@code x-ui.analytics} à operação canônica
 * {@code /stats/group-by} (ou {@code /filter} como fallback).
 * </p>
 *
 * <p>
 * No core atual do starter, esta é a forma recomendada de expor controllers que herdam
 * {@code AbstractResourceController}, {@code AbstractResourceQueryController},
 * {@code AbstractReadOnlyResourceController} ou
 * {@code AbstractCollectionCommandResourceController}. Ela evita que path e identidade do
 * recurso fiquem espalhados por convenções paralelas.
 * </p>
 *
 * <h3>Exemplo recomendado</h3>
 * <pre>{@code
 * @ApiResource(
 *     value = "/api/human-resources/employees",
 *     resourceKey = "human-resources.employees",
 *     identity = @ResourceIdentity(
 *         keyField = "id",
 *         titleField = "fullName",
 *         subtitleField = "roleName",
 *         metadataFields = {"employeeCode", "departmentName"}
 *     )
 * )
 * @ApiGroup("human-resources")
 * public class EmployeeController extends AbstractResourceController<...> {
 * }
 * }</pre>
 *
 * <p>
 * Não é necessário combinar {@code @ApiResource} com {@code @RestController}; a meta-anotação
 * já incorpora esse papel. Quando houver necessidade de agrupamento documental explícito, combine
 * com {@link ApiGroup}.
 * </p>
 *
 * @see org.praxisplatform.uischema.constants.ApiPaths
 * @see org.praxisplatform.uischema.annotation.ApiGroup
 * @see org.praxisplatform.uischema.annotation.ResourceIdentity
 * @see org.praxisplatform.uischema.annotation.QuickFilter
 * @see org.praxisplatform.uischema.annotation.UiAnalytics
 * @see org.praxisplatform.uischema.annotation.TableView
 * @see org.praxisplatform.uischema.annotation.UiFormLayout
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@RestController
@RequestMapping
public @interface ApiResource {

    /**
     * Define o path do recurso. Recomenda-se criar constantes especificas da aplicacao em vez de
     * repetir literais espalhados no codigo.
     *
     * @return o path do recurso
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "value")
    String[] value() default {};

    /**
     * Alias para {@link #value()} para maior clareza.
     *
     * @return o path do recurso
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "path")
    String[] path() default {};

    /**
     * Identidade semantica estavel do recurso.
     *
     * <p>
     * Este valor e usado por superficies derivadas de discovery, como os catalogos de surfaces e
     * actions, alem dos snapshots de capabilities. Diferentemente do path REST, ele nao deve
     * variar apenas porque a URL operacional mudou.
     * </p>
     *
     * @return chave semantica estavel do recurso
     */
    String resourceKey();

    /**
     * Titulo humano opcional do recurso para superficies de discovery e cockpits.
     *
     * <p>
     * Este valor descreve a identidade semantica do recurso, nao o label de um componente
     * especifico. Runtimes consumidores podem usa-lo como preferencia antes de derivar nomes de
     * {@link #resourceKey()} ou do path.
     * </p>
     *
     * @return titulo humano do recurso
     */
    String title() default "";

    /**
     * Descricao opcional do recurso para superficies de discovery e cockpits.
     *
     * @return descricao humana do recurso
     */
    String description() default "";

    /**
     * Icone semantico opcional do recurso.
     *
     * <p>
     * O valor deve ser um token estavel de biblioteca/runtime, por exemplo {@code groups},
     * {@code inventory_2} ou {@code analytics}. O starter apenas publica o token; a materializacao
     * visual cabe ao runtime consumidor.
     * </p>
     *
     * @return token de icone do recurso
     */
    String icon() default "";

    /**
     * Token opcional de tom visual do recurso.
     *
     * <p>
     * Use quando o dominio precisar sugerir um acento visual sem authorar CSS. Consumidores podem
     * mapear o token para paletas proprias.
     * </p>
     *
     * @return token de tom visual
     */
    String visualTone() default "";

    /**
     * Identidade visual estruturada dos registros deste recurso.
     *
     * <p>O runtime usa esta declaracao para compor cabecalhos, selecoes e resumos de registro
     * sem inferir semantica por nomes de propriedade ou decompor labels concatenados.</p>
     *
     * @return contrato de identidade dos registros
     */
    ResourceIdentity identity() default @ResourceIdentity;

    /**
     * Filtros rapidos canonicos declarados para a colecao deste recurso.
     *
     * <p>
     * Runtimes de tabela consomem estes filtros e os renderizam na barra de ferramentas
     * como seletores rapidos (chips) sem necessidade de configuracao manual no frontend.
     * </p>
     *
     * @return array de filtros rapidos
     */
    QuickFilter[] quickFilters() default {};

    /**
     * Define os content types produzidos pelo recurso.
     * Por padrao, produz JSON.
     *
     * @return os media types produzidos
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "produces")
    String[] produces() default {"application/json"};

    /**
     * Define os content types consumidos pelo recurso.
     * Por padrao, nao especifica content types e aceita todos.
     *
     * @return os media types consumidos
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "consumes")
    String[] consumes() default {};
}
