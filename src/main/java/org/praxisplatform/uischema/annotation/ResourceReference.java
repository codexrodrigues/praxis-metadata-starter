package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara que uma propriedade de resposta ou projecao referencia outro recurso canonico da
 * plataforma, permitindo que superficies de leitura (tabelas, listas, Quick View) oferecam
 * inspecao contextual in-place e navegacao governada para o registro referenciado.
 *
 * <p>Exemplo tipico em DTO de leitura ou projecao:</p>
 * <pre>{@code
 * @ResourceReference(
 *     resourceKey = "human-resources.funcionarios",
 *     valueField = "funcionarioId",
 *     surfaceId = "detail",
 *     previewSurfaceId = "hero-profile"
 * )
 * private String funcionarioNome;
 * }</pre>
 */
@Target({
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceReference {

    /**
     * Chave semantica canonica ({@link ApiResource#resourceKey()}) do recurso referenciado.
     */
    String resourceKey();

    /**
     * Nome do campo no mesmo DTO que contem o identificador do registro referenciado
     * (por exemplo {@code funcionarioId} quando a propriedade anotada e {@code funcionarioNome}).
     * Quando omitido, o runtime utiliza o valor do proprio campo anotado.
     */
    String valueField() default "";

    /**
     * Identificador da surface canonica de abertura completa do registro referenciado.
     */
    String surfaceId() default "detail";

    /**
     * Identificador opcional da surface preferencial para inspecao rapida (Quick View),
     * como uma projecao 360 ({@code hero-profile}). Quando omitido, utiliza {@link #surfaceId()}.
     */
    String previewSurfaceId() default "";

    /**
     * Apresentacao preferencial ao abrir a surface completa ({@code drawer}, {@code modal},
     * {@code fullscreen} ou {@code route}). No handoff de Quick View, {@code drawer} e {@code modal}
     * definem a apresentacao preferencial de overlay.
     */
    String detailPresentation() default "";
}
