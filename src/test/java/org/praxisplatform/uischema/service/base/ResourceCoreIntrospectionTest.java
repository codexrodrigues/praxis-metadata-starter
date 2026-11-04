package org.praxisplatform.uischema.service.base;

import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.util.CursorEncoder;
import org.springframework.data.domain.Sort;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ResourceCoreIntrospectionTest {
    @Test void inheritedGetterVersionAndPropertyIdentityAreDetectedAndAccessorCacheDoesNotCacheValues() {
        var service = new GetterService();
        GetterEntity entity = new GetterEntity(); entity.revision = 3;
        assertEquals(3, service.getEntityResourceVersion(entity).orElseThrow());
        entity.revision = 8;
        assertEquals(8, service.getEntityResourceVersion(entity).orElseThrow());
        assertEquals("key", service.getIdFieldName()); assertEquals(List.of("key"), service.getIdentityPaths());
        assertEquals(OptionalLong.empty(), service.getEntityResourceVersion(null));
    }

    @Test void cursorCodecRoundTripsColonsPercentsUnicodeNullAndEmptyStrings() {
        Sort sort = Sort.by("first", "second", "third");
        var tuple = new Tuple("A:%3A~ç", null, "");
        String token = ResourceKeysetSupport.encode(tuple, sort);
        assertEquals(Arrays.asList("A:%3A~ç", null, ""), ResourceKeysetSupport.decode(token, sort));
        assertFalse(token.contains("="));
        assertEquals(List.of("OPEN"), ResourceKeysetSupport.decode(ResourceKeysetSupport.encode(new EnumTuple(State.OPEN), Sort.by("state")), Sort.by("state")));
        assertThrows(IllegalArgumentException.class, () -> ResourceKeysetSupport.decode(token, Sort.by("first")));
        assertThrows(IllegalArgumentException.class, () -> ResourceKeysetSupport.decode(CursorEncoder.BASE64_URL.encode("%xx"), Sort.by("first")));
        assertEquals(List.of(""), ResourceKeysetSupport.decode(ResourceKeysetSupport.encode(new Tuple("", null, null), Sort.by("first")), Sort.by("first")));
    }

    @Test void exportIntrospectionUsesGetterAnnotationsAndPreventsContainerBypass() {
        assertEquals(List.of("name"), ResourceExportSupport.defaults(GetterResponse.class).stream().map(field -> field.key()).toList());
        assertFalse(ResourceExportSupport.exportable(GetterResponse.class, "nested"));
        assertFalse(ResourceExportSupport.exportable(GetterResponse.class, "nested.secret"));
        assertTrue(ResourceExportSupport.exportable(GetterResponse.class, "nested.safe"));
    }

    @Test void exportGovernanceTraversesGenericContainersAndRejectsOpaqueValues() {
        for (String field : List.of("entries", "index", "optional", "array", "box", "opaque", "json", "dynamic"))
            assertFalse(ResourceExportSupport.exportable(CollectionResponse.class, field), field);
        assertTrue(ResourceExportSupport.exportable(CollectionResponse.class, "labels"));
        assertTrue(ResourceExportSupport.exportable(RecursiveResponse.class, "node"));
    }

    @Test void concreteSubtypesAndUnannotatedBeanGettersCannotHideRestrictedDescendants() {
        assertThrows(IllegalArgumentException.class, () -> ResourceExportSupport.requireExportableValue(List.of(new RestrictedSubtype())));
        assertFalse(ResourceExportSupport.exportable(DerivedResponse.class, "derived"));
        assertFalse(ResourceExportSupport.exportable(InterfaceResponse.class, "secret"));
        assertThrows(IllegalArgumentException.class, () -> ResourceExportSupport.requireExportableValue(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("secret", "value")));
        assertSame("safe", ResourceExportSupport.requireExportableValue("safe"));
        assertDoesNotThrow(() -> ResourceExportSupport.requireExportableValue(Map.of("labels", List.of("safe"))));
    }
    public static class SafeBase { public String label; }
    public static class RestrictedSubtype extends SafeBase {
        @DomainGovernance(kind = DomainGovernanceKind.SECURITY, classification = DomainClassification.RESTRICTED,
                dataCategory = DomainDataCategory.CREDENTIAL, exportable = false) public String secret;
    }
    public static class Derived { public Nested getCalculated() { return new Nested(); } }
    public static class DerivedResponse { public Derived derived; }

    public interface GovernedProperty {
        @DomainGovernance(kind = DomainGovernanceKind.SECURITY, classification = DomainClassification.RESTRICTED,
                dataCategory = DomainDataCategory.CREDENTIAL, exportable = false) String getSecret();
    }
    public static class InterfaceResponse implements GovernedProperty { public String secret; public String getSecret() { return secret; } }
    public static class DynamicJson {
        @com.fasterxml.jackson.annotation.JsonAnyGetter public Map<String, String> values() { return Map.of("secret", "value"); }
    }
    public static class CollectionResponse {
        @UISchema public com.fasterxml.jackson.databind.JsonNode json;
        @UISchema public DynamicJson dynamic;
        @UISchema public List<Nested> entries;
        @UISchema public Map<String, Nested> index;
        @UISchema public Optional<Nested> optional;
        @UISchema public Nested[] array;
        @UISchema public Box<Nested> box;
        @UISchema public Map<String, Object> opaque;
        @UISchema public List<String> labels;
    }
    public static class Box<T> { public T value; }
    public static class RecursiveResponse { public Node<String> node; }
    public static class Node<T> { public T value; public Node<T> next; }

    public record Tuple(String first, String second, String third) { }
    public record EnumTuple(State state) { }
    public enum State { OPEN; @Override public String toString() { return "Display label"; } }
    public static class VersionParent { Integer revision; @Version public Integer getRevision() { return revision; } }
    public static class GetterEntity extends VersionParent { @Id public Long getKey() { return 1L; } }
    public static class Filter implements GenericFilterDTO { }
    static class GetterService extends AbstractReadOnlyResourceService<GetterEntity, String, Long, Filter> {
        @SuppressWarnings("unchecked") GetterService() { super(mock(BaseCrudRepository.class), GetterEntity.class); }
        @SuppressWarnings("unchecked") protected ResourceMapper<GetterEntity, String, ?, ?, Long> getResourceMapper() { return mock(ResourceMapper.class); }
    }
    public static class GetterResponse {
        @UISchema(order = 1) public String getName() { return "Name"; }
        @UISchema(order = 2) public Nested getNested() { return new Nested(); }
    }
    public static class Nested {
        public String safe;
        @DomainGovernance(kind = DomainGovernanceKind.SECURITY, classification = DomainClassification.RESTRICTED,
                dataCategory = DomainDataCategory.CREDENTIAL, exportable = false) public String secret;
    }
}
