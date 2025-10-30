package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Tipos de arquivos permitidos para upload/seleção em componentes de UI.
 * <p>
 * Os valores são serializados como MIME types via {@link #getValue()} (anotado
 * com {@link JsonValue}), permitindo que o frontend aplique restrições em inputs
 * de arquivo (accept) ou validadores.
 * </p>
 *
 * <h3>Exemplo</h3>
 * <pre>{@code
 * @UISchema(controlType = FieldControlType.FILE_UPLOAD)
 * private byte[] comprovante;
 *
 * // No frontend (accept): image/*, application/pdf
 * }</pre>
 *
 * @since 1.0.0
 */
public enum AllowedFileTypes {
    ALL("*/*"),
    IMAGES("image/*"),
    JSON("application/json"),
    XML("application/xml,text/xml"),
    HTML("text/html"),
    CSV("text/csv,application/csv,application/vnd.ms-excel"),
    PDF("application/pdf"),
    WORD("application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    TEXT("text/plain"),
    PPT("application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    ZIP("application/zip,application/x-zip-compressed"),
    AUDIO("audio/*"),
    VIDEO("video/*"),
    CUSTOM(""); // para casos onde o dev quer customizar manualmente

    private final String value;
    AllowedFileTypes(String value) { this.value = value; }

    /**
     * Valor MIME representando o tipo de arquivo aceito.
     *
     * @return string de MIME type (ex.: {@code image/*})
     */
    @JsonValue
    public String getValue() { return value; }
}
