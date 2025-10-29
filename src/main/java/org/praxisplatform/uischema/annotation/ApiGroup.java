package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação opcional para definir explicitamente o grupo OpenAPI de um controller.
 * <p>
 * Quando presente, substitui a lógica de derivação automática baseada no nome do controller
 * ou no caminho base, permitindo controle fino sobre a organização da documentação OpenAPI.
 * </p>
 * 
 * <h3>Exemplo de Uso:</h3>
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/human-resources/funcionarios")
 * @ApiGroup("rh-funcionarios")
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     // Controller implementation...
 * }
 * }</pre>
 * 
 * <p>
 * No exemplo acima, o grupo OpenAPI será "rh-funcionarios" em vez do grupo automaticamente
 * derivado "funcionarios", resultando na documentação estar disponível em:
 * {@code /v3/api-docs/rh-funcionarios}
 * </p>
 *
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 * @see org.praxisplatform.uischema.controller.base.AbstractCrudController
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiGroup {
    
    /**
     * O nome do grupo OpenAPI para este controller.
     * <p>
     * Este valor será usado como identificador do grupo na documentação OpenAPI,
     * aparecendo na URL {@code /v3/api-docs/{value}} e nos metadados da API.
     * </p>
     * 
     * @return o nome do grupo OpenAPI
     */
    String value();
}