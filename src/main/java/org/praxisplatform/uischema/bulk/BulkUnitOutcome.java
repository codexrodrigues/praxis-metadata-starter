package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/** Minimal protected receipt outcome; public messages are derived by the governed consumer. */
@JsonIgnoreType
public enum BulkUnitOutcome {
    CONFIRMED,
    UNCHANGED
}
