package org.praxisplatform.uischema.controller.cockpit;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.constants.ApiPaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PraxisCockpitControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PraxisCockpitController())
            .build();

    @Test
    void redirectsCockpitBasePathToBundledStaticPage() throws Exception {
        mockMvc.perform(get(ApiPaths.Framework.COCKPIT))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ApiPaths.Framework.COCKPIT_INDEX));
    }

    @Test
    void redirectsCockpitSlashPathToBundledStaticPage() throws Exception {
        mockMvc.perform(get(ApiPaths.Framework.COCKPIT + "/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ApiPaths.Framework.COCKPIT_INDEX));
    }

    @Test
    void bundlesCockpitStaticEntryPointInStarterJarResources() {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/index.html");

        assertThat(resource.exists()).isTrue();
    }

    @Test
    void bundledCockpitEntryPointExposesElementsExpectedByScript() throws IOException {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("id=\"resourceCount\"")
                .contains("id=\"surfaceCountHint\"")
                .contains("id=\"actionCountHint\"")
                .contains("id=\"capabilityMatrix\"")
                .contains("id=\"diagnosticsList\"");
    }
}
