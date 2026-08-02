package org.praxisplatform.uischema.action;

/**
 * Canonical transport used to carry the expected persisted resource version.
 */
public enum ActionResourceVersionTransport {
    NONE,
    IF_MATCH,
    SELECTION_MAP
}
