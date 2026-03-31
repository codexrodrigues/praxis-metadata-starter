package org.praxisplatform.uischema.controller.docs;

import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Exporta discovery semantico de workflow actions sem redefinir o contrato estrutural.
 */
@RestController
@RequestMapping("/schemas/actions")
public class ActionCatalogController {

    private final ActionCatalogService actionCatalogService;

    public ActionCatalogController(ActionCatalogService actionCatalogService) {
        this.actionCatalogService = actionCatalogService;
    }

    @GetMapping
    public ResponseEntity<ActionCatalogResponse> getActions(
            @RequestParam(name = "resource", required = false) String resourceKey,
            @RequestParam(name = "group", required = false) String group
    ) {
        boolean hasResource = StringUtils.hasText(resourceKey);
        boolean hasGroup = StringUtils.hasText(group);
        if (hasResource == hasGroup) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Exactly one of 'resource' or 'group' must be provided."
            );
        }

        ActionCatalogResponse response = hasResource
                ? actionCatalogService.findByResourceKey(resourceKey)
                : actionCatalogService.findByGroup(group);
        return ResponseEntity.ok(response);
    }
}
