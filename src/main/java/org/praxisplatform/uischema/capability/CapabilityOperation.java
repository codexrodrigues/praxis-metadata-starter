package org.praxisplatform.uischema.capability;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.praxisplatform.uischema.exporting.CollectionExportFormat;
import org.praxisplatform.uischema.exporting.CollectionExportScope;

import java.util.List;
import java.util.Map;

/**
 * Semantica operacional minima publicada para uma operacao canonica.
 *
 * <p>
 * Este record nao redefine payloads nem schemas. Ele entrega apenas o minimo necessario para que
 * runtimes clientes saibam se a operacao existe, em que escopo opera, qual metodo HTTP e
 * preferencial e qual decisao de availability se aplica ao contexto atual.
 * </p>
 */
public record CapabilityOperation(
        String id,
        boolean supported,
        String scope,
        String preferredMethod,
        String preferredRel,
        AvailabilityDecision availability,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<CollectionExportFormat> formats,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<CollectionExportScope> scopes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Integer> maxRows,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean async
) {

    public CapabilityOperation {
        availability = availability == null ? AvailabilityDecision.allowAll() : availability;
        formats = formats == null ? List.of() : List.copyOf(formats);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        maxRows = maxRows == null ? Map.of() : Map.copyOf(maxRows);
    }

    public CapabilityOperation(
            String id,
            boolean supported,
            String scope,
            String preferredMethod,
            String preferredRel,
            AvailabilityDecision availability
    ) {
        this(id, supported, scope, preferredMethod, preferredRel, availability, List.of(), List.of(), Map.of(), null);
    }

    public CapabilityOperation withAvailability(AvailabilityDecision nextAvailability) {
        return new CapabilityOperation(
                id,
                supported,
                scope,
                preferredMethod,
                preferredRel,
                nextAvailability,
                formats,
                scopes,
                maxRows,
                async
        );
    }
}
