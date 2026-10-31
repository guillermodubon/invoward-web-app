package io.github.guillermodubon.invoward.sharing.domain;

/** Derived presentation state; it is not persisted as a separate value. */
public enum ShareLinkStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
