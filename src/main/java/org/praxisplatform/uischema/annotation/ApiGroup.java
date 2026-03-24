package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação opcional para definir explicitamente o grupo OpenAPI de um controller.
 *
 * <p>
 * No contrato atual da plataforma, {@code @ApiGroup} e usado para estabilizar a navegacao da
 * documentacao publica sem depender apenas da derivacao automatica pelo nome do controller ou
 * pelo path do recurso. Isso e especialmente util quando varios recursos pertencem ao mesmo
 * contexto de negocio e devem aparecer agrupados sob a mesma superficie OpenAPI.
 * </p>
 *
 * <h3>Quando usar</h3>
 * <ul>
 *   <li>Quando o nome do grupo precisa refletir um bounded context de negocio, e nao apenas o path.</li>
 *   <li>Quando varios controllers devem compartilhar o mesmo agrupamento documental.</li>
 *   <li>Quando a plataforma publica docs por grupos estaveis consumidos por UI, recipes ou integradores.</li>
 * </ul>
 *
 * <h3>Exemplo recomendado</h3>
 * <pre>{@code
 * @ApiResource(ApiPaths.HumanResources.FUNCIONARIOS)
 * @ApiGroup("human-resources")
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     // heranca + wiring do service
 * }
 * }</pre>
 *
 * <p>
 * No exemplo acima, o grupo OpenAPI publicado passa a ser {@code human-resources}, o que ajuda a
 * manter uma URL de docs mais estavel, por exemplo {@code /v3/api-docs/human-resources}, mesmo
 * quando a convencao automatica derivada do nome da classe nao seria a mais adequada.
 * </p>
 *
 * <p>
 * A anotacao nao altera o path REST do recurso. Ela afeta a organizacao documental e a resolucao
 * de grupos feita pela infraestrutura OpenAPI do starter.
 * </p>
 *
 * @see org.praxisplatform.uischema.annotation.ApiResource
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 * @see org.praxisplatform.uischema.controller.base.AbstractCrudController
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiGroup {
    
    /**
     * Nome canônico do grupo OpenAPI para este controller.
     *
     * <p>
     * O valor informado sera usado como identificador do grupo na documentacao OpenAPI,
     * aparecendo tipicamente em URLs no formato {@code /v3/api-docs/{value}} e na resolucao
     * de metadados documentais do recurso.
     * </p>
     *
     * <p>
     * Recomenda-se usar nomes estaveis e semanticamente claros, como
     * {@code human-resources}, {@code catalog} ou {@code reporting}, evitando acoplamento
     * acidental ao nome tecnico da classe.
     * </p>
     *
     * @return o nome do grupo OpenAPI publicado para o controller
     */
    String value();
}
