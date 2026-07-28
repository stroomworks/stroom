# Coding standards & conventions (for implementing-agent plans)

The single, shared standards every Graph DB / query **implementation plan** in this folder points to, so the rules live in one place. **Follow the repository's existing, build-enforced standards — do not invent new ones.**

Referenced by: [`graphdb/epoch0-development-plan.md`](graphdb/epoch0-development-plan.md). The earlier Graph DB implementation plans that also pointed here have been retired to git history.

---

- **The build is the gate.** `./gradlew clean build` (mandated by [`CONTRIBUTING.md`](../CONTRIBUTING.md)) runs **Checkstyle** ([`config/checkstyle/checkstyle.xml`](../config/checkstyle/checkstyle.xml)) at `severity=error` — a single violation fails the build, so run it (or the module's `checkstyleMain` for a quick check) before treating any task as done. Rules to write to from the start: **max line length 120**; custom import order (`SPECIAL_IMPORTS → THIRD_PARTY → STANDARD_JAVA → STATIC`) with no unused imports; **braces on every control statement** (`NeedBraces`); one statement per line; canonical modifier order; `default` on every `switch`; and standard naming for parameters, records, and type parameters.

- **Match the file you're editing.** Conventions that are universal in this codebase (only some are Checkstyle-enforced): the **Apache-2.0 licence header** on every new file; `final` on parameters and fields; **JSpecify `@Nullable`** for nullability; **builder** classes for multi-field value types and `record`s / `sealed` interfaces for AST/IR nodes; Javadoc on public types/methods in the **Preconditions / Postconditions / Null-status** style used throughout the query modules; `@JsonProperty` on API/model fields.

- **Tests are part of "done".** Add or extend JUnit 5 tests in the module's `src/test/java`, mirroring the existing test classes the tasks name (e.g. `TestGraphTraversalEngine`, `TestLogicalPlan`, the join tests). New behaviour without a test is incomplete.

- **Client (GWT) code** follows the MVP presenter/view pattern of the sibling editors (e.g. `stroom.sqlstore.client.presenter`); register new presenters through the module's Gin `*Module`.

- **Housekeeping.** Work on a feature branch off `master`; add a CHANGELOG entry via `log_change.sh` (per `CONTRIBUTING.md`); flag any user-facing change for `stroom-docs` (documenting it there is out of scope for these plans, but note it).

- **Mind code drift.** Line numbers in the plans are hints; **names are contracts** — re-read a cited file before editing and confirm the signature/method still holds.

---

*A shared reference, not a plan. Keep it current with the repo's Checkstyle config and `CONTRIBUTING.md`; the plans link here rather than duplicating these rules.*
