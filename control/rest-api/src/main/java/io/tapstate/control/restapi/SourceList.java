package io.tapstate.control.restapi;

import io.tapstate.control.core.SourceView;

import java.util.List;

/** Ordered Source collection returned by the HTTP list endpoint. */
record SourceList(List<SourceView> items) {

    SourceList {
        items = List.copyOf(items);
    }
}
