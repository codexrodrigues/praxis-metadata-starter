package org.praxisplatform.uischema.multitenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * {@link CsrfTokenRepository} que isola tokens CSRF por realm/tenant, evitando colisoes de sessao ou
 * cookie quando multiplos realms sao operados pelo mesmo cliente/navegador.
 *
 * <p>Requer {@code spring-security-web} no classpath (dependencia opcional do starter).</p>
 */
public class RealmScopedCsrfTokenRepository implements CsrfTokenRepository {

    public static final String DEFAULT_SESSION_KEY_PREFIX = "PRAXIS_CSRF_TOKEN::";
    public static final String DEFAULT_COOKIE_PREFIX = "XSRF-TOKEN-";

    private final Function<HttpServletRequest, String> realmResolver;
    private final Function<String, CsrfTokenRepository> delegateFactory;
    private final ConcurrentMap<String, CsrfTokenRepository> delegates = new ConcurrentHashMap<>();

    public RealmScopedCsrfTokenRepository() {
        this(RealmScopedSecurityContextRepository.defaultRealmResolver(), false);
    }

    public RealmScopedCsrfTokenRepository(boolean cookieBased) {
        this(RealmScopedSecurityContextRepository.defaultRealmResolver(), cookieBased);
    }

    public RealmScopedCsrfTokenRepository(
            Function<HttpServletRequest, String> realmResolver,
            boolean cookieBased
    ) {
        this(realmResolver, realm -> {
            if (cookieBased) {
                CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
                repo.setCookieName(DEFAULT_COOKIE_PREFIX + sanitizeCookieSuffix(realm));
                return repo;
            }
            HttpSessionCsrfTokenRepository repo = new HttpSessionCsrfTokenRepository();
            repo.setSessionAttributeName(DEFAULT_SESSION_KEY_PREFIX + realm);
            return repo;
        });
    }

    public RealmScopedCsrfTokenRepository(
            Function<HttpServletRequest, String> realmResolver,
            Function<String, CsrfTokenRepository> delegateFactory
    ) {
        this.realmResolver = Objects.requireNonNull(realmResolver, "realmResolver must not be null");
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory must not be null");
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegateFor(request).generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        delegateFor(request).saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegateFor(request).loadToken(request);
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        return delegateFor(request).loadDeferredToken(request, response);
    }

    private CsrfTokenRepository delegateFor(HttpServletRequest request) {
        String realm = resolveRealm(request);
        return delegates.computeIfAbsent(realm, delegateFactory);
    }

    private String resolveRealm(HttpServletRequest request) {
        String resolved = request != null ? realmResolver.apply(request) : null;
        if (resolved == null || resolved.isBlank()) {
            return TenantRealmContext.get().orElse(RealmScopedSecurityContextRepository.DEFAULT_REALM);
        }
        String normalized = TenantRealmContext.normalize(resolved);
        return normalized != null ? normalized : RealmScopedSecurityContextRepository.DEFAULT_REALM;
    }

    private static String sanitizeCookieSuffix(String realm) {
        return realm.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
