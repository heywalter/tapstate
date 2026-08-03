package io.tapstate.core.common;

/**
 * The tapstate type namespace: what a discovered source column is, independent of the database that
 * declared it and of the connector framework that reported it.
 *
 * <p>A connector reports a column by its own database type string ({@code decimal(18,4)}), which is
 * meaningful only against that connector's own declarations. Every consumer that has to reason about a
 * column rather than merely carry it - whether a row expression over it can be evaluated without losing
 * precision, what a schema face displays - needs one shared vocabulary instead of each re-parsing the
 * database's spelling. This is that vocabulary; mapping a connector's own types onto it belongs to the
 * layer that owns the connector framework, never here.
 */
public enum TapstateType {

    /** Text. */
    STRING,

    /** An exact fixed-point number: it carries a scale no binary floating point type holds losslessly. */
    DECIMAL,

    /** A whole number that fits the 64-bit signed range. */
    INT64,

    /** A binary floating point number: the source holds it approximately already. */
    DOUBLE,

    /**
     * A column nothing resolved a type for. It is a named outcome rather than an absent one so that a
     * consumer deciding what may be done with a column has to rule on it, instead of an absent type
     * defaulting into whatever the consumer treats as harmless.
     */
    UNKNOWN
}
