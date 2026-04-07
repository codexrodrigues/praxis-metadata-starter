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
 *
 * <p>
 * Este endpoint publica apenas metadados semanticos de navegacao e disponibilidade. O schema
 * estrutural das operacoes referenciadas continua sendo resolvido por {@code /schemas/filtered}.
 * </p>
 */
@RestController
@RequestMapping("/schemas/surfaces")
public class SurfaceCatalogController {

    private final SurfaceCatalogService surfaceCatalogService;

    public SurfaceCatalogController(SurfaceCatalogService surfaceCatalogService) {
        this.surfaceCatalogService = surfaceCatalogService;
    }

    /**
     * Retorna surfaces por recurso ou por grupo documental.
     *
     * <p>
     * A operacao exige exatamente um dos parametros {@code resource} ou {@code group} para evitar
     * ambiguidades entre discovery por identidade semantica do recurso e discovery agregado por
     * grupo OpenAPI.
     * </p>
     */
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
