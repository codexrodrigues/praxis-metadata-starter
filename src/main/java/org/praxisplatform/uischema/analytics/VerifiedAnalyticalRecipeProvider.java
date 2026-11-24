package org.praxisplatform.uischema.analytics;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.List;

/**
 * Optional SPI allowing hosts or modules to contribute programmatic
 * {@link VerifiedAnalyticalRecipeDescriptor} definitions outside controller classes.
 */
public interface VerifiedAnalyticalRecipeProvider {

    /**
     * Returns additional verified analytical recipes for the given resource.
     */
    List<VerifiedAnalyticalRecipeDescriptor<? extends GenericFilterDTO>> findRecipes(
            String resourceKey,
            String resourcePath
    );
}
