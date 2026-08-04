package io.tapstate.control.client;

/** Request timeout class for light control reads and heavier probe/apply operations. */
public enum RequestBudget {
    LIGHT,
    HEAVY
}
