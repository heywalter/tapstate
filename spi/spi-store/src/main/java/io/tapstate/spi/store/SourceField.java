package io.tapstate.spi.store;

import io.tapstate.core.common.TapstateType;

import java.util.Objects;

/**
 * One field of a discovered stream: its name, the type its source declared it with, and the tapstate type
 * that resolves to. An immutable value.
 *
 * <p>{@code name} is always present. {@code dataType} is the type string the connector reported, kept
 * verbatim because it is what a sink needs to create the column again in a database of the same kind, and
 * null when discovery could not resolve it. {@code type} is the same column in the tapstate type
 * namespace - the form anything that reasons about the column rather than merely carrying it reads. It is
 * resolved where the connector is still open, since the database's own spelling means nothing away from
 * the connector that declared it; a field carrying no resolved type is
 * {@link TapstateType#UNKNOWN}, never null.
 */
public record SourceField(String name, String dataType, TapstateType type) {

    public SourceField {
        Objects.requireNonNull(name, "name");
        type = type == null ? TapstateType.UNKNOWN : type;
    }

    /** A field nothing resolved a tapstate type for: the source's own spelling and no more. */
    public SourceField(String name, String dataType) {
        this(name, dataType, TapstateType.UNKNOWN);
    }
}
