package org.praxisplatform.uischema.annotation;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.bulk.BulkMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkOperationContractTest {
    @Test
    void annotationIsExplicitRuntimeMethodMetadataWithOnlyGovernedMembers() throws Exception {
        assertEquals(RetentionPolicy.RUNTIME, BulkOperation.class.getAnnotation(java.lang.annotation.Retention.class).value());
        assertArrayEquals(new ElementType[]{ElementType.METHOD},
                BulkOperation.class.getAnnotation(java.lang.annotation.Target.class).value());

        var members = Arrays.stream(BulkOperation.class.getDeclaredMethods())
                .collect(java.util.stream.Collectors.toMap(java.lang.reflect.Method::getName,
                        java.lang.reflect.Method::getReturnType));
        assertEquals(java.util.Map.of(
                "mode", BulkMode.class,
                "evaluationOperationId", String.class,
                "atomicity", ActionCollectionAtomicity.class), members);
        for (var member : BulkOperation.class.getDeclaredMethods()) assertNull(member.getDefaultValue());
        assertTrue(BulkOperation.class.isAnnotationPresent(java.lang.annotation.Documented.class));
    }
}
