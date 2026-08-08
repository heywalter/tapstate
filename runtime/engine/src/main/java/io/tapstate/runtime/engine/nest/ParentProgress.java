package io.tapstate.runtime.engine.nest;

import java.util.OptionalLong;

/** What is known about the parent a held change is waiting for, at the moment the question is asked. */
public record ParentProgress(boolean levelLoaded, OptionalLong consumedEventTime) {
}
