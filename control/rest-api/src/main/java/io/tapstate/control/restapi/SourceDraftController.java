package io.tapstate.control.restapi;

import io.tapstate.control.core.SourceDraft;
import io.tapstate.control.core.SourceDraftResult;
import io.tapstate.control.core.SourceDraftService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Projects the non-persistent Source draft view onto the authenticated HTTP face. */
@RestController
class SourceDraftController {

    private final SourceDraftService drafts;

    SourceDraftController(SourceDraftService drafts) {
        this.drafts = drafts;
    }

    @Verb("source.draft")
    @PostMapping("/sources:draft")
    SourceDraftResult draft(@RequestBody SourceDraft request) {
        return drafts.draft(request);
    }
}
