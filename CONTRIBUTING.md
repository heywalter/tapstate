# Contributing to Tapstate

Thanks for your interest in Tapstate! Contributions are welcome.

## Workflow

1. **Fork** the repository and clone your fork.
2. **Branch** off `main` for your change: `git checkout -b my-change`.
3. **Build and test** locally before opening a PR:
   ```sh
   mvn verify
   ```
   This compiles every module and runs the unit tests, enforcer rules and the
   architecture (ArchUnit) checks. If you change the CLI and want to exercise the
   native binary, also build it with `mvn -Pnative -pl cli -am -DskipTests package`
   (requires GraalVM for JDK 21).
4. **Open a pull request** against `main`. Describe what changed and why. CI runs
   the build and a few repository checks on every PR — make sure it's green.

## End-to-end cases

**A change to product source is admitted only with an end-to-end case alongside it.**
A CI check enforces this on every pull request. It is not a coverage target: one
smoke-level case is the floor — a case that fails if your change is reverted.

**Write it declaratively first.** A case is a directory under `e2e/examples/`:

```
e2e/examples/<what-the-run-proves>/
  spec.e2e.yml       # the case itself: setup, seed, steps, assertions
  pipeline.tap.yml   # and the resources it applies
  ...
```

`spec.e2e.yml` is validated against `e2e/spec/e2e-spec.schema.json`, and every word
a specification may use is listed in `e2e/spec/matchers.json`. Prefer this form: it
runs the shipped product on both fidelity tiers and needs no Java.

**Fall back to Java when that vocabulary does not reach.** The list of words is
short, and a specification can only assert what something real answers — so you
will sometimes find no word for the behavior you changed. Then write
`e2e/src/test/java/<Something>IT.java` instead, and say in the pull request which
word was missing. That is how the vocabulary grows; a case you could not express
is a gap in the executor, not a reason to skip the case.

Out of scope, and passing without a case: documentation, build and CI
configuration, scripts, test-only changes, and the shared test scaffolding in
`test-support/` — none of it ships. If a product change genuinely
cannot carry a case, a maintainer applies the `no-e2e` label and records why in
the review — it is a reviewed exception, not something you assert about your own
change. The check verifies a case is *present*; whether it is *adequate* is the
reviewer's call.

## Guidelines

- **Java 21.** The build targets JDK 21; the native CLI requires GraalVM for JDK 21.
- **Comments and identifiers in English.** Keep code comments, Javadoc, test names
  and messages in English.
- **Keep commit messages clean.** Plain, descriptive messages; please don't paste
  automated-tool signature footers into commits or PR descriptions (a CI check
  rejects them).
- **Match the surrounding code.** Follow the conventions and structure of the
  module you're editing; the architecture tests enforce the dependency rules
  between modules.
- **Add tests** for behavior changes, and keep the existing ones green — including
  the end-to-end case described above.

## Reporting issues

Open a GitHub issue with a clear description and, where relevant, a minimal
reproduction (for the CLI, the `.tap.yml` input and the exact command).
