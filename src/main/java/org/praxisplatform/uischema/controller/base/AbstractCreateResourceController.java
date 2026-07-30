package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseCreateResourceService;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceCommandService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;

/**
 * Base canonica para resources que publicam leitura e criacao, sem atualizacao ou exclusao.
 *
 * <p>Use esta composicao quando a operacao de criacao possui gate fechado, mas editar ou excluir
 * ainda nao integra o contrato publico. Assim, OpenAPI, mappings e links nao anunciam operacoes
 * indisponiveis apenas porque compartilham o mesmo recurso.</p>
 */
public abstract class AbstractCreateResourceController<
        ResponseDTO,
        ID,
        FD extends GenericFilterDTO,
        CreateDTO
> extends AbstractResourceQueryController<ResponseDTO, ID, FD> {

    @Override
    protected abstract BaseCreateResourceService<ResponseDTO, ID, FD, CreateDTO> getService();

    @PostMapping
    @Operation(summary = "Criar item")
    public ResponseEntity<RestApiResponse<ResponseDTO>> create(@Valid @RequestBody CreateDTO dto) {
        assertCollectionOperationAvailable("create");
        BaseCreateUpdateResourceCommandService.SavedResult<ID, ResponseDTO> saved = getService().create(dto);
        ID newId = saved.id();
        Link selfLink = linkToSelf(newId);

        List<Link> links = new ArrayList<>();
        links.add(selfLink);
        links.add(linkToAll());
        links.add(linkToFilter());
        links.add(linkToFilterCursor());
        links.addAll(buildItemDiscoveryLinks(newId));
        links.add(linkToUiSchema("/", "post", "request"));

        return withVersion(
                ResponseEntity.created(selfLink.toUri()),
                RestApiResponse.success(saved.body(), hateoasOrNull(Links.of(links)))
        );
    }

    @Override
    protected List<Link> buildCollectionActionLinks() {
        List<Link> links = new ArrayList<>();
        if (isCollectionOperationAvailable("create")) {
            links.add(Link.of(resourcePath(), "create"));
        }
        links.addAll(super.buildCollectionActionLinks());
        return links;
    }
}
