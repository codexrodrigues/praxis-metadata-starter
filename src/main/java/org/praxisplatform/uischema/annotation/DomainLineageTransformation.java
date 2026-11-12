package org.praxisplatform.uischema.annotation;

/**
 * Classifica a transformacao semantica entre o(s) atributo(s) autoritativo(s) de dominio e a
 * representacao publica em um DTO, projecao ou filtro ({@code P44–P50}).
 *
 * <p>
 * Mudar o nome da propriedade ({@link #ALIAS}), formatar o valor ({@link #FORMATTED}), derivar
 * um indicador ({@link #DERIVED}) ou agregar valores ({@link #AGGREGATED}) nunca concede
 * desclassificacao automatica nem amplia permissoes de exportacao ou uso por IA.
 * </p>
 */
public enum DomainLineageTransformation {
    /** Representacao direta 1:1 do atributo de origem no mesmo significado de dominio. */
    DIRECT("direct"),
    /** Representacao direta do mesmo atributo de dominio sob nome JSON/Java distinto (ex.: {@code documentoFiscal} -> {@code cpf}). */
    ALIAS("alias"),
    /** Representacao formatada ou normalizada que preserva integralmente a classificacao da origem. */
    FORMATTED("formatted"),
    /** Valor ou indicador calculado a partir de um ou mais atributos de origem (ex.: enriquecimento pos-mapper ou formula). */
    DERIVED("derived"),
    /** Agregacao (contagem, soma, media, saldo) sobre atributos de origem; nao implica anonimizacao automatica. */
    AGGREGATED("aggregated"),
    /** Parametro de entrada de pesquisa/filtro que referencia o atributo de dominio pesquisado. */
    FILTER_PREDICATE("filter_predicate"),
    /** Atributo originado de fonte externa, view ou contrato sem entidade JPA dedicada. */
    EXTERNAL_PROJECTION("external_projection");

    private final String wireValue;

    DomainLineageTransformation(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
