# Golden records: pilot case 04 (Data Splitter)

**Do not show this file to the model under test.** The model is given `input/` only.

**What is different from cases 01 to 03.** This case tests the **first half** of a translation: a Data Splitter
configuration that turns raw text into records. Cases 01 to 03 gave the model the records. It is checked by
`tools/run-datasplitter-check.sh`, which compiles the configuration, runs it over the input, and compares the records
with `answers-do-not-show-to-model/expected-records.xml`, exactly.

## The task given to the model

A Data Splitter (`data-splitter:3`) configuration for `input/raw.csv` that makes one `<record>` per row, with one
unnamed `<data value="..."/>` per field in column order. The records are Stroom's `records:2` form.

## The input and what each part must produce

| Part of the input | Golden |
|---|---|
| `# gateway export 2026-11-04` | **No record**, and no error. A comment line |
| `...,"blocked, repeated scans"` | One record. The ninth field is `blocked, repeated scans`: the comma inside the quotes does not split it, and the quotes are removed |
| `...,tcp,allowed` | One record with nine fields |
| (a blank line) | **No record**, and no error |
| `...,"first line` and `second line"` | **One** record. The ninth field is `first line` and `second line` joined by the line break, not two records |
| `2026-11-04T10:00:04Z,gw04,DENY` | One record with **three** fields. A short row is kept, so a later step can report it |

Four records in all, no errors.

## Why an exact match matters

A **naive configuration** (split on the line break, then on commas) runs with **no errors**, covers **100% of the
input** and makes **six records**: the comment is a record, the quoted comma splits the field, and the two-line note
becomes two records. It scores perfectly on errors and on coverage and is wrong. This is the §2.3 problem at the
extraction stage, and the exact match is what catches it. (Verified: it prints `MATCH no`.)

## Decision points

| # | Decision | Ask? | Options | Intended answer |
|---|---|---|---|---|
| D1 | The `#` comment line | Ask | (a) not a record, no error (b) a record | (a) |
| D2 | A blank line | No | n/a | Not a record |
| D3 | A comma inside quotes | No (CSV convention) | n/a | Part of the field, quotes removed |
| D4 | A line break inside quotes | Ask | (a) one record, the break kept in the value (b) one record, the break replaced by a space (c) two records | (a) |
| D5 | A row with too few fields | Ask | (a) keep it with the fields present (b) drop it | (a) |
| D6 | Field names | No: the task says unnamed | n/a | Unnamed, in column order |

**What good looks like:** asks D1, D4 and D5, and does not ask D2, D3 or D6.
