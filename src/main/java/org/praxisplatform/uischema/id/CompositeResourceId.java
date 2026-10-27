package org.praxisplatform.uischema.id;

import java.io.Serializable;

/**
 * Optional contract for composite resource identifiers ({@code @Embeddable}).
 *
 * <p>Any {@link jakarta.persistence.Embeddable} record or class is automatically supported by
 * {@link CompositeIdSupport} without implementing this interface. Implementing
 * {@code CompositeResourceId} is optional and provides a convenient {@link #toWireId()} helper.</p>
 */
public interface CompositeResourceId extends Serializable {

    /**
     * Returns the canonical wire representation of this composite identifier.
     */
    default String toWireId() {
        return CompositeIdSupport.encode(this);
    }
}
