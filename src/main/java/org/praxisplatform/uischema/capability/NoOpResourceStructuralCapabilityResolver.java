package org.praxisplatform.uischema.capability;

/** Fallback conservador para hosts sem registry resource-oriented. */
public final class NoOpResourceStructuralCapabilityResolver implements ResourceStructuralCapabilityResolver {

    @Override
    public ResourceStructuralCapabilities resolve(String resourcePath) {
        return ResourceStructuralCapabilities.unsupported();
    }
}
