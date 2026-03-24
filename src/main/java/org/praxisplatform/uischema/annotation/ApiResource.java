package org.praxisplatform.uischema.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-anotacao que combina {@link RestController} e {@link RequestMapping} para declarar
 * recursos REST da plataforma de forma canonica.
 *
 * <p>
 * No ecossistema do starter, esta e a forma recomendada de expor controllers que herdam
 * {@code AbstractCrudController} ou {@code AbstractReadOnlyController}. Ela centraliza o
 * base path do recurso, reduz boilerplate e permite que a infraestrutura de documentacao,
 * HATEOAS e resolucao de schemas trabalhe sobre uma mesma fonte de verdade.
 * </p>
 *
 * <h3>O que ela entrega</h3>
 * <ul>
 *   <li>Registra o controller como bean REST sem exigir {@code @RestController} separado.</li>
 *   <li>Define o base path do recurso para endpoints CRUD, options, stats e schemas.</li>
 *   <li>Permite autodeteccao consistente do path pela infraestrutura OpenAPI do starter.</li>
 * </ul>
 *
 * <h3>Exemplo recomendado</h3>
 * <pre>{@code
 * // Recomenda-se definir constantes no projeto da aplicacao:
 * public final class ApiPaths {
 *     public static final class HumanResources {
 *         public static final String FUNCIONARIOS = "/api/human-resources/funcionarios";
 *     }
 * }
 *
 * @ApiResource(ApiPaths.HumanResources.FUNCIONARIOS)
 * @ApiGroup("human-resources")
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     // apenas heranca e wiring do service
 * }
 * }</pre>
 *
 * <h3>Forma alternativa</h3>
 * <pre>{@code
 * @ApiResource("/api/human-resources/funcionarios")
 * public class FuncionarioController extends AbstractCrudController<...> {
 * }
 * }</pre>
 *
 * <p>
 * Nao e necessario combinar {@code @ApiResource} com {@code @RestController}; a meta-anotacao
 * ja incorpora esse papel. Quando houver necessidade de organizacao documental explicita, combine
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
     * Define o path do recurso. Recomenda-se criar constantes 
     * específicas da aplicação em vez de usar as constantes do framework.
     * 
     * @return o path do recurso
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "value")
    String[] value() default {};
    
    /**
     * Alias para value() para maior clareza.
     * 
     * @return o path do recurso
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "path")
    String[] path() default {};
    
    /**
     * Define os content types produzidos pelo recurso.
     * Por padrão, produz JSON.
     * 
     * @return os media types produzidos
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "produces")
    String[] produces() default {"application/json"};
    
    /**
     * Define os content types consumidos pelo recurso.
     * Por padrão, não especifica content types (aceita todos).
     * 
     * @return os media types consumidos
     */
    @AliasFor(annotation = RequestMapping.class, attribute = "consumes")
    String[] consumes() default {};
}
