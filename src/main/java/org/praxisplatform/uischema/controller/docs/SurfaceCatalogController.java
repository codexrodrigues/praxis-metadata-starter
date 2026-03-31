package org.praxisplatform.uischema.controller.docs;

import org.praxisplatform.uischema.surface.SurfaceCatalogResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Exporta discovery semantico de surfaces sem redefinir o contrato estrutural.
 */
@RestController
@RequestMapping("/schemas/surfaces")
public class SurfaceCatalogController {

    private final SurfaceCatalogService surfaceCatalogService;

    public SurfaceCatalogController(SurfaceCatalogService surfaceCatalogService) {
        this.surfaceCatalogService = surfaceCatalogService;
    }

    @GetMapping
    public ResponseEntity<SurfaceCatalogResponse> getSurfaces(
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

        SurfaceCatalogResponse response = hasResource
                ? surfaceCatalogService.findByResourceKey(resourceKey)
                : surfaceCatalogService.findByGroup(group);
        return ResponseEntity.ok(response);
    }
}
