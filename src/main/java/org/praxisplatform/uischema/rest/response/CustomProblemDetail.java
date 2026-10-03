package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ProblemDetail;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Extensao padronizada de {@link ProblemDetail} usada pela plataforma.
 *
 * <p>
 * Alem dos campos RFC 7807, este tipo carrega uma mensagem resumida e uma
 * {@link ErrorCategory} padronizada, permitindo tratamento mais previsivel em UI,
 * observabilidade e integrações clientes.
 * </p>
 *
 * @since 1.0.0
 */
@Getter
@Setter
public class CustomProblemDetail extends ProblemDetail {

    private static final Set<String> RESERVED_MEMBERS = Set.of(
            "type", "title", "status", "detail", "instance",
            "message", "category", "code", "target", "properties");

    /** Mensagem específica do problema reportado. */
    private String message;

    /** Categoria do erro, para uso em UI e métricas. */
    private ErrorCategory category;

    /** Codigo publico estavel para tratamento sem parsing da mensagem. */
    private String code;

    /** Caminho opcional no contrato publico que o consumidor pode corrigir. */
    private String target;

    /**
     * Constrói o detalhe de problema com a mensagem informada.
     *
     * @param message detalhe textual do problema
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public CustomProblemDetail(@JsonProperty("message") String message) {
        this.message = message;
        this.category = null;
    }

    /**
     * Sets the typed correction target; null or blank denotes absence.
     */
    public void setTarget(String target) {
        this.target = target == null || target.isBlank() ? null : target;
    }

    /** Adds an extension without shadowing a typed or RFC problem member. */
    @Override
    public void setProperty(String name, Object value) {
        requireExtensionName(name);
        super.setProperty(name, value);
    }

    /** Replaces extensions atomically, retaining null values and caller isolation. */
    @Override
    public void setProperties(Map<String, Object> properties) {
        if (properties == null) {
            super.setProperties(null);
            return;
        }
        Map<String, Object> replacement = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            requireExtensionName(name);
            replacement.put(name, value);
        });
        super.setProperties(replacement);
    }

    /**
     * Returns an immutable, shallow snapshot of extensions, empty when absent.
     * Typed members are accessed through their getters, never through this map.
     */
    @Override
    @Schema(hidden = true)
    public Map<String, Object> getProperties() {
        Map<String, Object> properties = super.getProperties();
        return properties == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** JSON extensions are flat; the Java map container is never an input member. */
    @JsonSetter("properties")
    private void rejectPropertiesWrapper(Object value) {
        throw new IllegalArgumentException("Problem JSON must not contain the reserved properties wrapper");
    }

    private static void requireExtensionName(String name) {
        if (name == null || RESERVED_MEMBERS.contains(name)) {
            throw new IllegalArgumentException("Problem extension name must not be null or reserved: " + name);
        }
    }
}
