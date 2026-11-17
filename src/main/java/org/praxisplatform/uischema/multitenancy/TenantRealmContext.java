package org.praxisplatform.uischema.multitenancy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Portador {@link ThreadLocal} do realm ou tenant ativo na requisicao ou execucao corrente.
 */
public final class TenantRealmContext {

    private static final Pattern SAFE_REALM = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final ThreadLocal<String> CURRENT_REALM = new ThreadLocal<>();

    private TenantRealmContext() {
    }

    public static Optional<String> get() {
        return Optional.ofNullable(CURRENT_REALM.get());
    }

    public static void set(String realm) {
        String normalized = normalize(realm);
        if (normalized == null) {
            CURRENT_REALM.remove();
        } else {
            CURRENT_REALM.set(normalized);
        }
    }

    public static void clear() {
        CURRENT_REALM.remove();
    }

    public static Scope open(String realm) {
        String previous = CURRENT_REALM.get();
        set(realm);
        return () -> {
            if (previous == null) {
                CURRENT_REALM.remove();
            } else {
                CURRENT_REALM.set(previous);
            }
        };
    }

    public static String normalize(String realm) {
        if (realm == null || realm.isBlank()) {
            return null;
        }
        String trimmed = realm.trim();
        if (!SAFE_REALM.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid tenant realm identifier");
        }
        return trimmed;
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
