package org.praxisplatform.uischema.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SchemaCanonicalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaCanonicalizer canonicalizer = new SchemaCanonicalizer();

    @Test
    void objectKeyOrderProducesSameHash() throws Exception {
        ObjectNode a = JsonNodeFactory.instance.objectNode();
        a.put("b", 1);
        a.put("a", 2);

        ObjectNode b = JsonNodeFactory.instance.objectNode();
        b.put("a", 2);
        b.put("b", 1);

        JsonNode ca = canonicalizer.canonicalize(a);
        JsonNode cb = canonicalizer.canonicalize(b);

        String ha = SchemaHashUtil.sha256Hex(ca);
        String hb = SchemaHashUtil.sha256Hex(cb);

        assertEquals(ha, hb, "Hashes must match after canonicalization");
    }

    @Test
    void requiredArrayIsSortedButEnumOrderPreserved() throws Exception {
        String json = "{\n"+
                "  \"required\": [\"b\", \"a\"],\n"+
                "  \"enum\": [\"y\", \"x\"]\n"+
                "}";
        JsonNode node = mapper.readTree(json);
        JsonNode c = canonicalizer.canonicalize(node);

        assertEquals("a", c.get("required").get(0).asText());
        assertEquals("b", c.get("required").get(1).asText());
        // enum order preserved
        assertEquals("y", c.get("enum").get(0).asText());
        assertEquals("x", c.get("enum").get(1).asText());
    }

    @Test
    void numericNormalizationStripsTrailingZeros() throws Exception {
        JsonNode n1 = mapper.readTree("{\"n\":1}");
        JsonNode n2 = mapper.readTree("{\"n\":1.0}");

        JsonNode c1 = canonicalizer.canonicalize(n1);
        JsonNode c2 = canonicalizer.canonicalize(n2);

        String h1 = SchemaHashUtil.sha256Hex(c1);
        String h2 = SchemaHashUtil.sha256Hex(c2);

        assertEquals(h1, h2, "1 and 1.0 should produce equal hashes");
    }
}

