package io.tapstate.control.restapi;

import io.tapstate.control.core.TokenInfo;

import java.util.List;

/** Secret-free machine-token list response. */
record TokenList(List<TokenInfo> tokens) {
}
