package org.praxisplatform.uischema.capability;

/**
 * Semantica operacional minima publicada para uma operacao canonica de CRUD.
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
        AvailabilityDecision availability
) {

    public CapabilityOperation {
        availability = availability == null ? AvailabilityDecision.allowAll() : availability;
    }

    public CapabilityOperation withAvailability(AvailabilityDecision nextAvailability) {
        return new CapabilityOperation(
                id,
                supported,
                scope,
                preferredMethod,
                preferredRel,
                nextAvailability
        );
    }
}
