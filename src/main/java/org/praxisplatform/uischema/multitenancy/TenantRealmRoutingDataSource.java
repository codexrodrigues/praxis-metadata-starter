package org.praxisplatform.uischema.multitenancy;

import org.praxisplatform.uischema.customfields.ResourceTenantContextResolver;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link AbstractRoutingDataSource} opcional que roteia conexoes por realm/tenant ativo.
 *
 * <p>Ordem de resolucao: {@link TenantRealmContext#get()}, depois um fallback customizado ou
 * {@link ResourceTenantContextResolver}, e por fim {@code defaultLookupKey} quando configurado.</p>
 */
public class TenantRealmRoutingDataSource extends AbstractRoutingDataSource {

    private Supplier<String> realmLookup = () -> TenantRealmContext.get().orElse(null);
    private Object defaultLookupKey;

    public TenantRealmRoutingDataSource() {
    }

    public TenantRealmRoutingDataSource(ResourceTenantContextResolver tenantContextResolver) {
        Objects.requireNonNull(tenantContextResolver, "tenantContextResolver must not be null");
        this.realmLookup = () -> TenantRealmContext.get()
                .orElseGet(() -> tenantContextResolver.resolveTenant().orElse(null));
    }

    public TenantRealmRoutingDataSource(Supplier<String> realmLookup) {
        this.realmLookup = Objects.requireNonNull(realmLookup, "realmLookup must not be null");
    }

    public void setRealmLookup(Supplier<String> realmLookup) {
        this.realmLookup = Objects.requireNonNull(realmLookup, "realmLookup must not be null");
    }

    public void setDefaultLookupKey(Object defaultLookupKey) {
        this.defaultLookupKey = defaultLookupKey;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String realm = realmLookup != null ? realmLookup.get() : null;
        if (realm != null && !realm.isBlank()) {
            return realm;
        }
        return defaultLookupKey;
    }
}
