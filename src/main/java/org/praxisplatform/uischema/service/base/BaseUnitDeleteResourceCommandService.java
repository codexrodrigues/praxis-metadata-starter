package org.praxisplatform.uischema.service.base;

/**
 * Boundary canonico de escrita para resources que permitem exclusao item a item, sem contrato de
 * exclusao em lote.
 *
 * <p>
 * Use este contrato quando a evidencia de rota ou governanca do recurso aprovar
 * {@code DELETE /{id}}, mas nao aprovar {@code DELETE /batch}. Recursos CRUD completos continuam
 * usando {@link BaseResourceCommandService}.
 * </p>
 */
public interface BaseUnitDeleteResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO>
        extends BaseCreateUpdateResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {

    void deleteById(ID id);
}
