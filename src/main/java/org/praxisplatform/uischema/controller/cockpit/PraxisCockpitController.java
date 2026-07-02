package org.praxisplatform.uischema.controller.cockpit;

import org.praxisplatform.uischema.constants.ApiPaths;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Entry point for the metadata starter cockpit bundled with the starter jar.
 *
 * <p>The cockpit itself is a static asset under {@code META-INF/resources} so every host that
 * installs the starter can open it without copying frontend files into the application.</p>
 */
@Controller
public class PraxisCockpitController {

    @GetMapping({ApiPaths.Framework.COCKPIT, ApiPaths.Framework.COCKPIT + "/"})
    public String cockpit() {
        return "redirect:" + ApiPaths.Framework.COCKPIT_INDEX;
    }
}
