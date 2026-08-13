package io.tapstate.control.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One normalized table selector in a Source draft. */
public record SourceTableDraft(
        String type,
        String name,
        String pattern,
        String filter,
        List<String> pk,
        Map<String, Object> options) {

    public SourceTableDraft {
        pk = pk == null ? null : Collections.unmodifiableList(new ArrayList<>(pk));
        options = SourceDraft.copyJsonMap(options, true);
    }
}
