package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara um criterio individual tipado de filtragem dentro de {@link QuickFilter#criteria()}.
 *
 * <p>
 * Aproveita a sintaxe nativa de arrays de elemento unico do Java (JLS §9.7.1) para permitir
 * declaracoes enxutas sem chaves ({@code values = "HOMOLOGADO"}, {@code bool = true},
 * {@code bool = false}, {@code number = 90}, {@code decimal = 12.5}) e sem ambiguidade entre
 * valor omitido ({@code length == 0}) e literais {@code false}/{@code 0} explicitos.
 * </p>
 *
 * <p>
 * Durante a publicacao de {@code x-ui.resource.quickFilters} e de {@code /schemas/domain}, o
 * starter resolve o {@code FilterDTO} ({@code GenericFilterDTO}) do controller e converte cada
 * criterio conforme o tipo real da propriedade anotada com {@code @Filterable}: colecoes
 * ({@code List<T>}) sao sempre emitidas como arrays JSON mesmo quando contiverem um unico item, e
 * codigos textuais (ex.: {@code "001"}) preservam o tipo {@code String}.
 * </p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface FilterCriterion {

    /**
     * Nome exato da propriedade anotada com {@code @Filterable} no {@code FilterDTO} do recurso.
     *
     * @return nome da propriedade de filtro
     */
    String field();

    /**
     * Um ou mais valores textuais, constantes de {@code Enum} ou literais temporais ISO-8601.
     * Exemplo: {@code values = "HOMOLOGADO"} ou {@code values = {"PENDENTE_ENVIO", "EM_ANALISE_TCE"}}.
     *
     * @return valores textuais/enum/data do criterio
     */
    String[] values() default {};

    /**
     * Valor booleano explicito do criterio (ex.: {@code bool = true} ou {@code bool = false}).
     *
     * @return literal booleano
     */
    boolean[] bool() default {};

    /**
     * Um ou mais valores numericos inteiros do criterio (ex.: {@code number = 90} ou {@code number = 0}).
     *
     * @return literais inteiros
     */
    long[] number() default {};

    /**
     * Um ou mais valores numericos decimais do criterio (ex.: {@code decimal = 12.5}).
     *
     * @return literais decimais
     */
    double[] decimal() default {};
}
