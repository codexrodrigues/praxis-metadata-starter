package org.praxisplatform.uischema.capability;

/**
 * SPI host-neutral para disponibilidade dinamica de operacoes canonicas de recurso.
 *
 * <p>
 * Hosts corporativos podem plugar guards legados, politicas de estado, autorizacao ou periodo
 * operacional aqui sem vazar esses detalhes para DTOs, schemas, links ou metadata publica.
 * O provider recebe IDs estaveis de CRUD, query, options, stats e export. A decisao publicada
 * orienta discovery e HATEOAS; enforcement HTTP continua responsabilidade do host.
 * </p>
 */
public interface ResourceOperationAvailabilityProvider {

    /**
     * Avalia a disponibilidade da operacao canonica no contexto informado.
     */
    AvailabilityDecision evaluate(ResourceOperationAvailabilityContext context);
}
