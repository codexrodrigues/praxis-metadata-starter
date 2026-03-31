package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma operacao resource-oriented tipada que representa uma intencao semantica de escrita
 * parcial sobre o mesmo recurso.
 *
 * <p>
 * Esta anotacao nao cria endpoints dinamicos nem substitui o contrato canonico do metodo. O
 * endpoint continua sendo definido por uma operacao HTTP real, como {@code @PatchMapping}, com
 * DTO proprio e documentacao OpenAPI normal. {@code @ResourceIntent} apenas adiciona uma camada
 * semantica explicita para intents como {@code profile}, {@code bank-details} ou
 * {@code preferences}.
 * </p>
 *
 * <p>
 * Ela e a base para a fase de escrita parcial por intencao do starter: multiplos formularios da
 * mesma entidade continuam resource-oriented, com operacoes reais e tipadas, sem transformar
 * {@code surface} em contrato de escrita.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceIntent {

    /**
     * Identificador estavel da intencao dentro do recurso.
     */
    String id();

    /**
     * Titulo humano da intencao.
     */
    String title();

    /**
     * Descricao opcional da finalidade do endpoint.
     */
    String description() default "";

    /**
     * Ordem sugerida para catalogos/documentacao futuros.
     */
    int order() default 0;
}
