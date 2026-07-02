package org.praxisplatform.uischema.service.base;

/**
 * Porta canonica de comando para resources mutaveis delegados a backend legado.
 *
 * <p>
 * A semantica publica continua resource-oriented. O host decide se a execucao real usa stored
 * procedure, command handler, adaptador mainframe, servico externo ou outro mecanismo privado.
 * </p>
 */
public interface LegacyBackedResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO>
        extends BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {
}
