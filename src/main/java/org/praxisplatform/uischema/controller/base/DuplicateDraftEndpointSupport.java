package org.praxisplatform.uischema.controller.base;

import java.util.ArrayList;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceStateSnapshotScope;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.DuplicateDraftResourceCommandService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** One non-persistent, fail-closed draft operation shared by the opt-in controller compositions. */
final class DuplicateDraftEndpointSupport {
    private DuplicateDraftEndpointSupport() { }
    static <R, ID, F extends GenericFilterDTO, D> ResponseEntity<RestApiResponse<D>> prepare(
            AbstractResourceController<R, ID, F, ?, ?> controller, DuplicateDraftResourceCommandService<ID, D> service, ID id) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            if (!service.supportsDuplicateDraft()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "duplicate-draft is not supported by this resource.");
            if (!available(controller, id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "duplicate-draft-unavailable");
            D draft = service.duplicateDraft(id);
            var links = new ArrayList<Link>();
            links.add(controller.linkToSelf(id));
            if (createAvailable(controller)) links.add(controller.linkToCreate());
            links.add(controller.linkToUiSchema("/{id}/duplicate-draft", "post", "request"));
            links.add(controller.linkToUiSchema("/{id}/duplicate-draft", "post", "response"));
            return controller.withVersion(ResponseEntity.ok(), RestApiResponse.success(draft, controller.hateoasOrNull(Links.of(links))));
        }
    }
    static <ID> boolean available(AbstractResourceQueryController<?, ID, ?> controller, ID id) {
        var capabilities = controller.getCapabilityService();
        String key = controller.getResourceKeyOrNull();
        if (id == null || capabilities == null || !StringUtils.hasText(key) || !StringUtils.hasText(controller.getBasePath())) return false;
        try {
            return controller.withOperationAvailabilityScope(() ->
                    allowed(capabilities.collectionOperationAvailability(key, controller.getBasePath(), "create"))
                    && allowed(capabilities.itemOperationAvailability(key, controller.getBasePath(), "view", id))
                    && allowed(capabilities.itemOperationAvailability(key, controller.getBasePath(), "duplicate-draft", id)));
        } catch (RuntimeException ex) { return false; }
    }
    private static boolean createAvailable(AbstractResourceQueryController<?, ?, ?> controller) {
        var capabilities = controller.getCapabilityService();
        String key = controller.getResourceKeyOrNull();
        if (capabilities == null || !StringUtils.hasText(key)) return false;
        try { return allowed(capabilities.collectionOperationAvailability(key, controller.getBasePath(), "create")); }
        catch (RuntimeException ex) { return false; }
    }
    private static boolean allowed(AvailabilityDecision decision) { return decision != null && decision.allowed(); }
}

