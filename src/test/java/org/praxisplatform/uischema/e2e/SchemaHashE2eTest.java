package org.praxisplatform.uischema.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchemaHashE2eTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String q) { return "http://localhost:" + port + "/schemas/filtered" + q; }

    @Test
    void e2e_ETagAnd304_WithRealDTOs() {
        // 1) First fetch (request schema for POST /e2e)
        ResponseEntity<String> r1 = rest.getForEntity(url("?path=/e2e&operation=post&schemaType=request&includeInternalSchemas=true"), String.class);
        assertEquals(200, r1.getStatusCodeValue(), "First call should return 200");
        String etag = r1.getHeaders().getETag();
        assertNotNull(etag, "ETag must be present");
        assertTrue(r1.getHeaders().getCacheControl() != null && r1.getHeaders().getCacheControl().contains("must-revalidate"));

        // 2) Conditional fetch (If-None-Match)
        HttpHeaders h = new HttpHeaders();
        h.set("If-None-Match", etag);
        ResponseEntity<String> r2 = rest.exchange(url("?path=/e2e&operation=post&schemaType=request&includeInternalSchemas=true"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertEquals(304, r2.getStatusCodeValue(), "Second call should return 304 when ETag matches");
        assertNull(r2.getBody(), "304 must not contain a body");

        // 3) Payload variation: toggle includeInternalSchemas for a schema with $ref
        ResponseEntity<String> r3 = rest.getForEntity(url("?path=/e2e/with-ref&schemaType=response&includeInternalSchemas=false"), String.class);
        assertEquals(200, r3.getStatusCodeValue());
        String etagReq = r3.getHeaders().getETag();
        assertNotNull(etagReq);
        ResponseEntity<String> r4 = rest.getForEntity(url("?path=/e2e/with-ref&schemaType=response&includeInternalSchemas=true"), String.class);
        assertEquals(200, r4.getStatusCodeValue());
        String etagReq2 = r4.getHeaders().getETag();
        assertNotNull(etagReq2);
        assertNotEquals(etagReq, etagReq2, "Expanding $ref must change ETag");
    }
}
