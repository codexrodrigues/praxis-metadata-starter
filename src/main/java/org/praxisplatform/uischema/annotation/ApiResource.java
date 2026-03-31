package org.praxisplatform.uischema.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-anotacao canonica para declarar um recurso REST da plataforma.
 *
 * <p>
 * {@code @ApiResource} combina {@link RestController} e {@link RequestMapping} e passa a ser a
 * fonte de verdade do recurso em tres eixos complementares:
 * </p>
 *
 * <ul>
 *   <li>path REST do recurso, publicado no OpenAPI e usado pelos controllers canonicos;</li>
 *   <li>identidade semantica estavel via {@link #resourceKey()}, usada por discovery;</li>
 *   <li>metadados basicos para scanner OpenAPI, HATEOAS e resolucao de schemas filtrados.</li>
 * </ul>
 *
 * <p>
 * No core atual do starter, esta e a forma recomendada de expor controllers que herdam
 * {@code AbstractResourceController}, {@code AbstractResourceQueryController} ou
 * {@code AbstractReadOnlyResourceController}. Ela evita que path e identidade do recurso fiquem
 * espalhados por convencoes paralelas.
 * </p>
 *
 * <h3>Exemplo recomendado</h3>
 * <pre>{@code
 * @ApiResource(
 *     value = "/api/human-resources/employees",
 *     resourceKey = "human-resources.employees"
 * )
 * @ApiGroup("human-resources")
 * public class EmployeeController extends AbstractResourceController<...> {
 * }
 * }</pre>
 *
 * <p>
 * Nao e necessario combinar {@code @ApiResource} com {@code @RestController}; a meta-anotacao
 * ja incorpora esse papel. Quando houver necessidade de agrupamento documental explicito, combine
 * com {@link ApiGroup}.
 * </p>
 *
 * @see org.praxisplatform.uischema.constants.ApiPaths
 * @see org.praxisplatform.uischema.annotation.ApiGroup
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
     * Este valor e usado por superficies derivadas de discovery, como o catalogo de surfaces.
     * Diferentemente do path REST, ele nao deve variar apenas porque a URL operacional mudou.
     * </p>
     *
     * <p>
     * @return chave semantica estavel do recurso
     */
    String resourceKey();

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
