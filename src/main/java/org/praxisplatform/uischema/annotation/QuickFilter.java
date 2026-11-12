package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara um filtro rapido canonico associado ao recurso ({@link ApiResource#quickFilters()}).
 *
 * <p>
 * Filtros rapidos sao publicados em {@code x-ui.resource.quickFilters} (nos schemas de leitura e de
 * filtro) e em {@code /schemas/domain} como recortes operacionais nomeados para interfaces humanas
 * ({@code <praxis-scope-bar>} e toolbars de tabela) e agentes de IA.
 * </p>
 *
 * <p>
 * Prefira sempre {@link #criteria()} com {@link FilterCriterion} tipado, que dispensa JSON escapado
 * e aplica coercao guiada pelos tipos reais do {@code FilterDTO} do controller. O atributo
 * {@link #filter()} permanece suportado apenas para compatibilidade retroativa.
 * </p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface QuickFilter {

    /**
     * Identificador unico do filtro rapido dentro do recurso.
     *
     * @return identificador do filtro rapido
     */
    String id();

    /**
     * Rotulo conciso exibido no chip de filtro rapido (ate 36 caracteres, sem ponto final).
     *
     * @return rotulo do filtro
     */
    String label();

    /**
     * Criterios tipados aplicados ao {@code FilterDTO} do recurso quando o filtro rapido esta ativo.
     *
     * @return lista de criterios tipados
     */
    FilterCriterion[] criteria() default {};

    /**
     * Expressao legada ({@code "chave=valor"} ou objeto JSON) aplicada ao clicar no chip.
     *
     * @return expressao de filtro legada
     * @deprecated Prefira {@link #criteria()} com {@link FilterCriterion} para tipagem forte e sem JSON escapado.
     */
    @Deprecated
    String filter() default "";

    /**
     * Icone semantico opcional (ligadura Material Icons em {@code snake_case} minusculo) exibido no chip.
     *
     * @return token do icone
     */
    String icon() default "";

    /**
     * Tom semantico opcional exibido no chip ({@code <praxis-scope-bar>} e toolbar).
     *
     * @return tom semantico do filtro rapido
     */
    QuickFilterTone tone() default QuickFilterTone.UNSPECIFIED;

    /**
     * Indica se este recorte deve iniciar ativo por padrao ao abrir a colecao.
     * No maximo um {@code @QuickFilter} por recurso pode declarar {@code defaultActive = true}.
     *
     * @return {@code true} quando ativo por padrao
     */
    boolean defaultActive() default false;

    /**
     * Descricao executiva de dominio explicando o significado operacional do recorte para tooltips,
     * catalogo semantico ({@code /schemas/domain}) e grounding de IA.
     *
     * @return descricao de negocio opcional
     */
    String description() default "";

    /**
     * Ordem explicita de apresentacao dos chips (empates preservam a ordem de declaracao).
     *
     * @return ordem de exibicao
     */
    int order() default 0;
}
