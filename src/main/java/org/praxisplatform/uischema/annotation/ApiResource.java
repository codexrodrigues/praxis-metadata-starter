package org.praxisplatform.uischema.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-anotação que combina @RestController e @RequestMapping para simplificar
 * a declaração de controllers de recursos REST.
 * 
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * // Recomenda-se definir constantes no projeto da aplicação:
 * public final class ApiPaths {
 *     public static final class HumanResources {
 *         public static final String FUNCIONARIOS = "/api/human-resources/funcionarios";
 *     }
 * }
 * 
 * // Usar a constante no controller:
 * @ApiResource(ApiPaths.HumanResources.FUNCIONARIOS)
 * @ApiGroup("human-resources")
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     // implementação do controller
 * }
 * 
 * // Ou usar path direto:
 * @ApiResource("/api/human-resources/funcionarios")
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     // implementação do controller
 * }
 * }</pre>
 * 
 * <p>Esta anotação elimina a necessidade de declarar múltiplas anotações
 * e promove o uso de constantes para os paths da API.</p>
 * 
 * @see org.praxisplatform.uischema.constants.ApiPaths
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
