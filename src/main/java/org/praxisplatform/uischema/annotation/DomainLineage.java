package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara a linhagem semantica explicita e verificavel de um campo de DTO, projecao ou filtro em
 * relacao ao(s) seu(s) atributo(s) autoritativo(s) de dominio ({@code P44–P50}, {@code P60}, {@code P64}).
 *
 * <p>
 * Permite que representacoes diretas, aliases ({@code documentoFiscal} -> {@code cpf}), campos
 * derivados/enriquecidos pos-mapper e parametros de filtro herdem deterministicamente a
 * governanca ({@link DomainGovernance}) declarada na origem sem duplicar anotacoes completas e
 * mantendo uma identidade semantica unica ({@link #attributeId()}) no catalogo {@code /schemas/domain}.
 * </p>
 *
 * <p>
 * Quando combinado com {@link DomainGovernance} local no proprio campo do DTO, a resolucao compoe
 * a governanca da origem com a declaracao local via {@code DomainSchemaGovernance.mergeOrCopy}
 * (permitindo apenas estreitamento restritivo e recusando enfraquecimento da classificacao da origem).
 * </p>
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainLineage {

    /**
     * Identificador semantico canonico e estavel do atributo de dominio (ex.: {@code "ergon.pessoa.cpf"}).
     * Quando omitido e {@link #sourceClass()} + um unico {@link #sourcePaths()} forem informados,
     * e derivado deterministicamente da origem.
     */
    String attributeId() default "";

    /**
     * Classe de dominio autoritativa (Entidade JPA, Value Object ou contrato de dominio) onde o(s)
     * caminho(s) de {@link #sourcePaths()} e sua governanca canonica estao declarados.
     * Pode permanecer {@link Void} para fontes externas ou projecoes sem classe de entidade ({@code P47}).
     */
    Class<?> sourceClass() default Void.class;

    /**
     * Caminho(s) de propriedade na origem (suporta navegacao pontilhada como {@code "dados.cpf"}
     * ou multiplas origens para campos derivados como {@code {"remuneracaoBruta", "descontos"}}).
     */
    String[] sourcePaths() default {};

    /**
     * Chave canonica opcional do recurso/conceito proprietario da origem (ex.: {@code "ergon.pessoas"}).
     */
    String sourceResourceKey() default "";

    /**
     * Natureza da transformacao entre o(s) atributo(s) de origem e esta representacao.
     */
    DomainLineageTransformation transformation() default DomainLineageTransformation.DIRECT;
}
