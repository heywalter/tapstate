package io.tapstate.runtime.engine.nest;

import io.tapstate.core.common.TapstateException;

import java.util.Map;

/**
 * The limit on how much one key may hold for something that has not arrived, enforced the same way at both
 * levels that hold anything.
 *
 * <p>One place rather than one each because the two levels hold for different reasons and would drift into
 * saying so differently. A resolver key holds the rows beneath a parent it cannot name yet; a document holds
 * what it absorbed under a root that has not come and what is parked for an ancestor. What an operator sees
 * when either fills up is the same news - this key is stuck and this much is stuck behind it - and it is
 * worth being one sentence rather than two that drifted apart.
 */
final class NestLimits {

    private NestLimits() {
    }

    /**
     * Fails the job when {@code pending} is past {@code limit}, naming where and how much. The limit is what
     * may be held, so reaching it exactly is allowed and the count that goes past it is refused.
     */
    static void refuse(NestVertex vertex, Object key, long pending, long limit) {
        if (pending <= limit) {
            return;
        }
        throw new TapstateException(NestError.PENDING_LIMIT_EXCEEDED,
                Map.of("namespace", vertex.mapName(), "key", String.valueOf(key),
                        "pending", pending, "limit", limit), null);
    }
}
