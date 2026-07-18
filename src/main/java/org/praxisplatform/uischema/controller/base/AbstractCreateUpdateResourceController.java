package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.rest.response.RestApiErrorResponse;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceCommandService;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;

/**
 * Base canonica mutante parcial do core resource-oriented.
 *
 * <p>
 * Esta classe adiciona create e update sobre {@link AbstractResourceQueryController}, sem publicar
 * endpoints, links ou affordances de delete. Use-a para recursos corporativos cujo contrato publico
 * permite manutencao parcial, mas cuja exclusao esta ausente, bloqueada ou fora do escopo.
 * </p>
 */
public abstract class AbstractCreateUpdateResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractResourceQueryController<ResponseDTO, ID, FD> {

    @Override
    protected abstract BaseCreateUpdateResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractCreateUpdateResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>> getCreateUpdateResourceControllerClass() {
        return (Class<? extends AbstractCreateUpdateResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>>) getClass();
    }

    @PostMapping
    @Operation(summary = "Criar item")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Resource created", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Invalid request or business rule violation", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Create operation is not available", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Request conflicts with existing or dependent data", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Entity is functionally invalid", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Sanitized unexpected failure", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class)))
    })
    public ResponseEntity<RestApiResponse<ResponseDTO>> create(@jakarta.validation.Valid @RequestBody CreateDTO dto) {
        assertCollectionOperationAvailable("create");
        BaseCreateUpdateResourceCommandService.SavedResult<ID, ResponseDTO> saved = getService().create(dto);
        ID newId = saved.id();
        ResponseDTO body = saved.body();
        Link selfLink = linkToSelf(newId);

        List<Link> linkList = new ArrayList<>();
        linkList.add(selfLink);
        linkList.add(linkToAll());
        linkList.add(linkToFilter());
        linkList.add(linkToFilterCursor());
        linkList.addAll(buildWriteResponseActionLinks(newId));
        linkList.addAll(buildItemDiscoveryLinks(newId));
        linkList.add(linkToUiSchema("/", "post", "request"));

        return withVersion(
                ResponseEntity.created(selfLink.toUri()),
                RestApiResponse.success(body, hateoasOrNull(Links.of(linkList)))
        );
    }

    @PutMapping("/{id}")
    @Operation(summary = "Editar item")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resource updated", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Invalid request or business rule violation", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Update operation is not available", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Resource was not found", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Request conflicts with existing or dependent data", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "412", description = "Resource version precondition failed", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Entity is functionally invalid", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Sanitized unexpected failure", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class)))
    })
    public ResponseEntity<RestApiResponse<ResponseDTO>> update(
            @PathVariable ID id,
            @jakarta.validation.Valid @RequestBody UpdateDTO dto
    ) {
        assertItemOperationAvailable("edit", id);
        ResponseDTO updated = getService().update(id, dto);

        List<Link> linkList = new ArrayList<>();
        linkList.add(linkToSelf(id));
        linkList.add(linkToAll());
        linkList.add(linkToFilter());
        linkList.add(linkToFilterCursor());
        linkList.addAll(buildWriteResponseActionLinks(id));
        linkList.addAll(buildItemDiscoveryLinks(id));
        linkList.add(linkToUiSchema("/{id}", "put", "request"));

        return withVersion(ResponseEntity.ok(), RestApiResponse.success(updated, hateoasOrNull(Links.of(linkList))));
    }

    @Override
    protected List<Link> buildItemActionLinks(ID id) {
        return isItemOperationAvailable("edit", id) ? List.of(linkToUpdate(id)) : List.of();
    }

    @Override
    protected List<Link> buildEntityActionLinks(ID id) {
        return isItemOperationAvailable("edit", id) ? List.of(linkToUpdate(id)) : List.of();
    }

    protected List<Link> buildWriteResponseActionLinks(ID id) {
        return isItemOperationAvailable("edit", id) ? List.of(linkToUpdate(id)) : List.of();
    }

    @Override
    protected List<Link> buildCollectionActionLinks() {
        List<Link> links = new ArrayList<>();
        if (isCollectionOperationAvailable("create")) {
            links.add(linkToCreate());
        }
        links.addAll(super.buildCollectionActionLinks());
        return links;
    }

    protected Link linkToCreate() {
        return Link.of(resourcePath(), "create");
    }

    protected Link linkToUpdate(ID id) {
        return Link.of(resourcePath(id), "update");
    }
}
