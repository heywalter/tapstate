#!/usr/bin/env bash
# Decides whether a pull request is admitted: a change to product source is admitted only with an
# end-to-end case alongside it.
#
# In scope: any */src/main/ file. Documentation, build and CI configuration, scripts and test-only
# changes are out of scope and pass without a case. What satisfies it: a declarative specification
# under e2e/examples/, or an end-to-end test in the e2e module. The waiver is a label a maintainer
# applies, so that an exception is a reviewed decision rather than the author's own word.
#
# Run inside the repository being judged. Reads two environment variables:
#   BASE_REF   the branch the pull request targets; its merge base with HEAD is what "changed" means
#   PR_LABELS  the pull request's labels, comma separated (may be empty)
# Exits 0 admitted, 1 refused.
set -euo pipefail

base="origin/${BASE_REF}"
git rev-parse --verify --quiet "$base" >/dev/null 2>&1 \
  || git fetch --quiet --no-tags origin "${BASE_REF}"
merge_base="$(git merge-base "$base" HEAD)"

# Two lists: everything the pull request touches, which decides scope, and the subset that still
# exists, which is what may satisfy the rule. Deleting a specification is not bringing one, so
# removals count towards scope but never towards the case.
changed="$(git diff --name-only "$merge_base" HEAD)"
present="$(git diff --name-only --diff-filter=d "$merge_base" HEAD)"

# "Product source" means source that ships. test-support is the one module whose main sources are
# test scaffolding: nothing there reaches a released artifact, and asking it for an end-to-end case
# would mean waiving every change to it - which is how a waiver stops meaning anything.
product="$(printf '%s\n' "$changed" | grep -E '(^|/)src/main/' | grep -v '^test-support/' || true)"
if [ -z "$product" ]; then
  echo "not in scope: this pull request changes no product source."
  exit 0
fi

cases="$(printf '%s\n' "$present" \
  | grep -E '^e2e/examples/[^/]+/spec\.e2e\.yml$|^e2e/src/test/java/.*IT\.java$' || true)"
if [ -n "$cases" ]; then
  echo "end-to-end case(s) in this pull request:"
  printf '%s\n' "$cases"
  exit 0
fi

case ",${PR_LABELS:-}," in
  *,no-e2e,*)
    echo "::warning::admission waived by the no-e2e label. The reason belongs in the review."
    exit 0
    ;;
esac

echo "::error::product source changed, and this pull request brings no end-to-end case."
echo "In scope because these changed:"
printf '%s\n' "$product"
cat <<'EOF'

Bring one of:

  e2e/examples/<what-the-run-proves>/spec.e2e.yml   plus the resources it applies.
      Every word a specification may use is listed in e2e/spec/matchers.json. Prefer this
      form: it runs on both fidelity tiers and needs no Java.

  e2e/src/test/java/<Something>IT.java              when no word reaches the behavior.
      Say in the pull request which word was missing - that is how the vocabulary grows.

One smoke-level case is the floor, not full coverage: a case that fails if the change is
reverted. Name it in the pull request description.

If the change genuinely cannot carry one, a maintainer applies the `no-e2e` label and says
why in the review.
EOF
exit 1
