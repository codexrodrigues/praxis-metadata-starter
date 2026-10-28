package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BulkCatalogFunctionBodiesTest {
    @Test void knownDigestRestoresExactPackagedBytesWithoutNormalizing() {
        var dictionary=BulkCatalogFunctionBodies.packaged();
        assertThat(dictionary.size()).isGreaterThan(50);
        dictionary.forEach((hash,body)-> {
            assertThat(BulkCatalogFunctionBodies.digest(body)).isEqualTo(hash);
            assertThat(BulkCatalogFunctionBodies.restore(hash,null)).isEqualTo(body);
        });
        assertThatThrownBy(()->dictionary.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void unknownAndAlteredBodiesTravelVerbatimAndNeverBorrowAnotherDigest() {
        String body="begin return 'unrecognized body'; end";
        String digest=BulkCatalogFunctionBodies.digest(body);
        assertThat(BulkCatalogFunctionBodies.restore(digest,body)).isEqualTo(body);
        assertThatThrownBy(()->BulkCatalogFunctionBodies.restore(digest,null)).isInstanceOf(IllegalStateException.class);
        String known=BulkCatalogFunctionBodies.packaged().keySet().iterator().next();
        assertThatThrownBy(()->BulkCatalogFunctionBodies.restore(known,body)).isInstanceOf(IllegalStateException.class);
        String whitespace=" \n"+BulkCatalogFunctionBodies.packaged().get(known);
        assertThat(BulkCatalogFunctionBodies.restore(BulkCatalogFunctionBodies.digest(whitespace),whitespace)).isEqualTo(whitespace);
    }
    @Test void omittedOrMalformedDigestFailsClosed() {
        for(String hash:new String[]{null,"","f","F".repeat(64),"0".repeat(64)})
            assertThatThrownBy(()->BulkCatalogFunctionBodies.restore(hash,null)).isInstanceOf(IllegalStateException.class);
    }
}
