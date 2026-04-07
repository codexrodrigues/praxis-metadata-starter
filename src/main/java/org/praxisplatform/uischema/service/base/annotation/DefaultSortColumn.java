package org.praxisplatform.uischema.service.base.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara a ordenacao padrao de um campo quando a consulta nao recebe sort explicito.
 *
 * <p>
 * A anotacao e consumida pelo fluxo canonico de query services do starter para construir a
 * ordenacao default aplicada a listagens e filtros. O objetivo e manter uma apresentacao estavel
 * e previsivel sem obrigar cada consumidor a informar sort em toda chamada.
 * </p>
 *
 * <p>
 * O processamento segue tres regras simples:
 * </p>
 *
 * <ol>
 *   <li>campos anotados sao coletados pela infraestrutura de query;</li>
 *   <li>a prioridade menor vence e define a ordem composta;</li>
 *   <li>um sort explicito da requisicao continua tendo precedencia sobre esta anotacao.</li>
 * </ol>
 *
 * <p>
 * Recomenda-se usar a anotacao apenas em campos adequados para ordenacao frequente e,
 * preferencialmente, indexados. Em recursos grandes, isso ajuda a evitar defaults arbitrarios e
 * reduz o risco de experiencias inconsistentes entre consumidores.
 * </p>
 *
 * @see org.praxisplatform.uischema.service.base.BaseResourceQueryService#getDefaultSort()
 * @see org.praxisplatform.uischema.controller.base.AbstractResourceQueryController
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DefaultSortColumn {
    /**
     * Direcao da ordenacao para o campo anotado.
     */
    boolean ascending() default true;

    /**
     * Prioridade do campo na ordenacao composta; menor valor significa maior precedencia.
     */
    int priority() default 0;
}
