package org.praxisplatform.uischema.bulk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable byte dictionary, never a cache of live catalog facts or authorization decisions. */
final class BulkCatalogFunctionBodies {
    private BulkCatalogFunctionBodies() { }
    private static final class Packaged {
        static final Map<String,String> BODIES = collect(BulkMigrationLineage.resolve(List.of()));
    }
    static Map<String,String> packaged() { return Packaged.BODIES; }
    static String digest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static Map<String,String> collect(BulkMigrationLineage source) {
        var result = new LinkedHashMap<String,String>();
        // These are trusted, packaged SQL resources. Unrecognized syntax merely disables compression.
        var pattern = Pattern.compile("(?is)create\\s+(?:or\\s+replace\\s+)?function\\s+praxis_bulk\\.[a-z_][a-z_0-9]*\\s*\\([^)]*\\).*?\\bas\\s*(\\$(?:[a-z_][a-z_0-9]*)?\\$)(.*?)\\1\\s*;");
        for (int version=1; version<=BulkMigrationLineage.lastVersion(); version++) {
            var matches = pattern.matcher(source.sql(version));
            while (matches.find()) {
                String body = matches.group(2), hash = digest(body);
                String previous = result.putIfAbsent(hash,body);
                if (previous!=null && !previous.equals(body)) throw new IllegalStateException("Packaged function digest collision");
            }
        }
        return Collections.unmodifiableMap(result);
    }
    static String restore(String digest, String transferredBody) {
        if (digest==null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Live function digest is absent or malformed");
        if (transferredBody!=null) {
            if (!digest.equals(digest(transferredBody))) throw new IllegalStateException("Live function digest differs from transferred body");
            return transferredBody;
        }
        String exact = packaged().get(digest);
        if (exact==null) throw new IllegalStateException("Omitted live function body has no exact packaged counterpart");
        return exact;
    }
}
