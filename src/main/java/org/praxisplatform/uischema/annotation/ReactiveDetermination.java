package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.determination.ReactiveDeterminationProvenanceKind;
import org.praxisplatform.uischema.determination.ReactiveDeterminationTriggerMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara uma Determinacao Reativa em Tempo de Formulario diretamente no metodo HTTP nao-persistente
 * do Controller ({@code POST .../determinations/...}), eliminando classes manuais de configuracao
 * {@code ReactiveDeterminationDefinitionProvider} no projeto host ({@code PLAT-EVO-16}).
 *
 * <p>O metodo anotado deve tambem declarar {@code @Operation(operationId = "...")} com identificador
 * estavel; o provider canonico extrai esse {@code operationId} automaticamente e valida todos os
 * caminhos em {@code ReactiveDeterminationMetadataCompiler} contra os schemas reais de OpenAPI.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReactiveDetermination {

    /**
     * Identificador estavel e globalmente unico da determinacao (ex.: {@code "ergon.folha.fichaFinanceira.previa"}).
     */
    String id();

    /**
     * {@code operationId} OpenAPI da operacao {@code POST} de criacao do recurso (atalho CRUD em par com {@link #updateOperationId()}).
     */
    String createOperationId() default "";

    /**
     * {@code operationId} OpenAPI da operacao {@code PUT} de edicao do recurso (atalho CRUD em par com {@link #createOperationId()}).
     */
    String updateOperationId() default "";

    /**
     * Escopos explicitos de formulario quando a determinacao nao usar o par CRUD padrao ou possuir escopos adicionais.
     */
    DeterminationScope[] scopes() default {};

    /**
     * Modo de disparo estrutural no formulario.
     */
    ReactiveDeterminationTriggerMode triggerMode() default ReactiveDeterminationTriggerMode.ON_INIT_AND_CHANGE;

    /**
     * Lista opcional de campos gatilho ({@code sourcePaths}). Quando vazia e o {@link #triggerMode()}
     * exigir caminhos de origem, todos os {@link DeterminationInput} com {@code triggerSource = true}
     * sao promovidos automaticamente para {@code sourcePaths}.
     */
    String[] sourceFields() default {};

    /**
     * Bindings de entrada entre campos do formulario e propriedades do payload da determinacao.
     */
    DeterminationInput[] inputs();

    /**
     * Bindings de saida entre propriedades do response da determinacao e campos do formulario.
     */
    DeterminationOutput[] outputs();

    /**
     * Origem estrutural do binding para rastreabilidade em {@code /schemas/filtered} e {@code /schemas/domain}.
     */
    ReactiveDeterminationProvenanceKind provenanceKind() default ReactiveDeterminationProvenanceKind.HOST;

    /**
     * Identificador estavel da capacidade ou modulo que declara o binding.
     */
    String provenanceSource() default "annotated-determination";

    /**
     * Versao estrutural do contrato da determinacao.
     */
    String provenanceVersion() default "1";
}
