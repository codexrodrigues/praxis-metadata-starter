package org.praxisplatform.uischema.capability;

/** Resolve o suporte estrutural executavel associado a um resource path canonico. */
public interface ResourceStructuralCapabilityResolver {

    ResourceStructuralCapabilities resolve(String resourcePath);
}
