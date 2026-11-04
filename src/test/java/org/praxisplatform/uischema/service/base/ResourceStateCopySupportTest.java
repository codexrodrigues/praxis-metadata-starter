package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Embeddable;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceStateCopySupportTest {
    @Test void patchTracksCanonicalJsonNamesIncludingAliasesAndNamingStrategy() throws Exception {
        var json = new ObjectMapper();
        var target = new RenamedTarget(); target.setNote("before");
        var patch = json.readValue("{\"legacyNote\":\"after\"}", RenamedPatch.class);
        ResourceStateCopySupport.copySuppliedPatchProperties(patch, target, json);
        assertEquals("after", target.getNote());
        var defaultsTarget = new RenamedTarget(); defaultsTarget.setNote("before");
        ResourceStateCopySupport.copySuppliedPatchProperties(json.readValue("{\"externalNote\":null}", RenamedPatch.class), defaultsTarget);
        assertNull(defaultsTarget.getNote());
        var snake = new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var snakeTarget = new SnakeTarget(); snakeTarget.setPostalCode("before");
        ResourceStateCopySupport.copySuppliedPatchProperties(snake.readValue("{\"postal_code\":\"1000\"}", SnakePatch.class), snakeTarget, snake);
        assertEquals("1000", snakeTarget.getPostalCode());
    }
    @lombok.Getter @lombok.Setter public static class RenamedTarget { private String note; }
    @lombok.Getter public static class RenamedPatch extends org.praxisplatform.uischema.dto.PartialUpdateInput {
        @com.fasterxml.jackson.annotation.JsonProperty("externalNote") @com.fasterxml.jackson.annotation.JsonAlias("legacyNote")
        private String note;
        public void setNote(String value) { supplied("externalNote"); note = value; }
    }
    @lombok.Getter @lombok.Setter public static class SnakeTarget { private String postalCode; }
    @lombok.Getter public static class SnakePatch extends org.praxisplatform.uischema.dto.PartialUpdateInput {
        private String postalCode;
        public void setPostalCode(String value) { supplied("postal_code"); postalCode = value; }
    }
    @Test void jsonAccessorsWithAcronymsDoNotDependOnSpringBeanNames() throws Exception {
        var patch = new ObjectMapper().readValue("{\"url\":\"after\"}", AcronymPatch.class);
        var target = new AcronymTarget();
        ResourceStateCopySupport.copySuppliedPatchProperties(patch, target);
        assertEquals("after", target.getURL());
    }
    public static class AcronymPatch extends org.praxisplatform.uischema.dto.PartialUpdateInput {
        private String value;
        public String getURL() { return value; }
        public void setURL(String value) { supplied("url"); this.value = value; }
    }
    public static class AcronymTarget {
        private String value = "before";
        public String getURL() { return value; }
        public void setURL(String value) { this.value = value; }
    }
    @Test void typedPatchPreservesAbsenceAndCopiesExplicitNullFalseAndZeroWithoutAliases() {
        var target = new PatchTarget();
        target.setName("Before"); target.setEnabled(true); target.setCount(7);
        var patch = new Patch();
        ResourceStateCopySupport.copySuppliedPatchProperties(patch, target);
        assertEquals("Before", target.getName()); assertTrue(target.getEnabled()); assertEquals(7, target.getCount());
        patch.setName(null); patch.setEnabled(false); patch.setCount(0);
        patch.setItems(new ArrayList<>(List.of("before")));
        ResourceStateCopySupport.copySuppliedPatchProperties(patch, target);
        patch.getItems().set(0, "after");
        assertNull(target.getName()); assertFalse(target.getEnabled()); assertEquals(0, target.getCount());
        assertEquals(List.of("before"), target.getItems());
    }

    @lombok.Getter @lombok.Setter public static class PatchTarget {
        private String name;
        private Boolean enabled;
        private Integer count;
        private List<String> items;
    }
    @lombok.Getter public static class Patch extends org.praxisplatform.uischema.dto.PartialUpdateInput {
        private String name;
        private Boolean enabled;
        private Integer count;
        private List<String> items;
        public void setName(String value) { supplied("name"); name = value; }
        public void setEnabled(Boolean value) { supplied("enabled"); enabled = value; }
        public void setCount(Integer value) { supplied("count"); count = value; }
        public void setItems(List<String> value) { supplied("items"); items = value; }
    }

    @Test void previousStateSeparatesNestedContainersEmbeddablesDatesAndJson() {
        var original = new Values();
        original.map.put("items", new ArrayList<>(List.of("before")));
        original.array = new int[]{1, 2};
        original.date = new Date(1234);
        original.address = new Address(); original.address.city = "Before";
        original.json = new ObjectMapper().createObjectNode().put("value", "before");
        var previous = new Values();
        var copies = new IdentityHashMap<Object, Object>(); copies.put(original, previous);
        ResourceStateCopySupport.copyFields(original, previous, copies);
        original.map.get("items").set(0, "after"); original.array[0] = 9;
        original.date.setTime(9999); original.address.city = "After"; original.json.put("value", "after");
        assertEquals(List.of("before"), previous.map.get("items"));
        assertArrayEquals(new int[]{1, 2}, previous.array); assertEquals(1234, previous.date.getTime());
        assertEquals("Before", previous.address.city); assertEquals("before", previous.json.get("value").asText());
    }

    @Test void cyclesArePreservedAndUnknownMutableObjectsRequireAnExplicitSnapshot() {
        var original = new ArrayList<Object>(); original.add(original);
        var previous = (List<?>) ResourceStateCopySupport.copy(original, new IdentityHashMap<>());
        assertNotSame(original, previous); assertSame(previous, previous.getFirst());
        assertThrows(IllegalStateException.class, () -> ResourceStateCopySupport.copy(new StringBuilder("mutable"), new IdentityHashMap<>()));
    }

    static class Values {
        Map<String, List<String>> map = new LinkedHashMap<>();
        int[] array;
        Date date;
        Address address;
        com.fasterxml.jackson.databind.node.ObjectNode json;
    }
    @Embeddable public static class Address { public String city; }
}
