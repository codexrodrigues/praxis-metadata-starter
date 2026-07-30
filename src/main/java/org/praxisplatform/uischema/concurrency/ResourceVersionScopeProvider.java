package org.praxisplatform.uischema.concurrency;

/**
 * Resolves the trusted isolation scope used to issue and validate record-version ETags.
 *
 * <p>Multi-tenant hosts should provide a bean backed by their authenticated tenant/environment
 * context. The starter supplies a global default for applications whose identifiers are globally
 * unique.</p>
 */
@FunctionalInterface
public interface ResourceVersionScopeProvider {

    ResourceVersionScope currentScope();
}
