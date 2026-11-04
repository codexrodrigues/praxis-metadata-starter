package org.praxisplatform.uischema.bulk;

import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.springframework.core.ResolvableType;

/** Resolves domain identity independently of the protocol's String/Integer wire identity. */
public final class BulkResourceTypes {
    private BulkResourceTypes() { }

    public static Class<?> identityType(Class<?> controller) {
        Class<?> id = ResolvableType.forClass(controller).as(AbstractResourceQueryController.class)
                .getGeneric(1).resolve();
        if (id == null || id == Object.class) throw new IllegalArgumentException("Bulk resource ID must be concrete");
        return id;
    }

    public static BulkIdentityCodec<?, ?> identityCodec(Class<?> id) {
        if (id == Integer.class || id == int.class) return BulkIdentityCodecs.integers();
        if (id == Long.class || id == long.class) return BulkIdentityCodecs.longs();
        if (id == String.class) return BulkIdentityCodecs.strings();
        if (id == java.util.UUID.class) return BulkIdentityCodecs.uuids();
        if (CompositeIdSupport.isCompositeIdType(id)) return BulkIdentityCodecs.composite(id);
        throw new IllegalArgumentException("Unsupported bulk resource identity type: " + id.getName());
    }
}
