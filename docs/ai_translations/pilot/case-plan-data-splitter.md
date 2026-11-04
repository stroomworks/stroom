# Plan: a Data Splitter pilot case (not built)

**Status:** **done** (see `case-04-splitter/` and `tools/run-datasplitter-check.sh`). The text below is the original
plan. A networked `./gradlew` build works here, which my first, offline attempt did not, so the harness was written
and the case verified. It has not yet been run on a model.

**Why it matters.** Cases 01 to 03 test only the XSLT half of a translation. The model is handed `parsed-records.xml`,
so the hard part of the first half (splitting raw text into records and fields) is not tested. A real translation is
a Data Splitter configuration plus an XSLT, and the scoring in the design note (input coverage, yield, the record
boundary) is mostly about the first half.

## What the case should contain

| Item | Detail |
|---|---|
| **Raw input** | A short synthetic log with the awkward things splitters get wrong: a quoted field containing a comma, a record that spans two lines, a blank line, a comment line, a trailing delimiter, and a line with the wrong number of fields |
| **The task for the model** | Write a Data Splitter configuration (`.ds3.xml`) for the input. Then, in a second step, the XSLT from case 02 or 03 |
| **Golden "events"** | For the splitter, the expected `records:2` output for the input (the same form as `stroom-pipeline/src/test/resources/TestDS3/*.out.xml`), plus **input coverage** (every character accounted for) and the **record count** |
| **Decision points** | Does a quoted comma split the field? Is a continuation line part of the previous record? Is a header or comment a record? What happens to a short row? |
| **Checks** | Run the configuration over the input, compare the records with the expected output, report coverage and errors |

## What is needed to run it, and what I found

- **The runner exists in the repository.** `DataSplitterRunner.run(compiled, input)` in
  `stroom-shapeshifter-ai` runs a compiled configuration headlessly and returns the records, the input span of each
  record, and the diagnostics (`ExtractionResult`). `DataSplitterCompiler` compiles a configuration. The existing
  corpus is `stroom-pipeline/src/test/resources/TestDS3`.
- **It cannot be run from here.** There are no built classes or jars (`stroom-pipeline/build` does not exist), and
  an offline Gradle build failed on plugin resolution. **A networked Gradle build was not attempted.** The two ways
  forward are a machine where `./gradlew` works, or running the configuration in Stroom's stepper.
- **What I would write once a build works:** a small test in `stroom-shapeshifter-ai` that takes a candidate
  configuration and the input and prints the records, coverage and errors, so the pilot's `run-checks.sh` can be
  extended to this case. That is a code change, so it needs your go-ahead.

## Decision for the project owner

Which would you prefer?

1. **I write the test harness** (a code change in `stroom-shapeshifter-ai`, for a node-less run) and the case, once a
   Gradle build works in this environment.
2. **You run the case in Stroom's stepper**, and I supply the case and a checklist.
3. **Defer it**, and spend the effort on the API comparison.
