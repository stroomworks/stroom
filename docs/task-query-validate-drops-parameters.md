# `validateQuery` supplies no parameters, so a parameterised query fails validation but runs fine

**Component:** `stroom-query-impl` — `QueryServiceImpl.validateQuery`, and `QueryResource.validateQuery`
**Severity:** low today, medium once `param()` is used more widely. Nothing misbehaves at search
time; the *validator* reports an error for a query that executes correctly, which is the more
confusing direction for the error to point.
**Status:** open. Known and deliberately left out of the server-side parameter change, because
closing it means changing a REST signature.

---

## What happens

`QueryServiceImpl.validateQuery` builds its request from the query text alone:

```java
public ValidateExpressionResult validateQuery(final String expressionString) {
    final QuerySearchRequest searchRequest = QuerySearchRequest.builder().query(expressionString).build();
    final SearchRequest mappedRequest = mapRequest(searchRequest);
```

There is no `QueryContext`, so `mapRequest` leaves `params` null, and `SearchRequestFactory` ends up
with an empty `paramMap`. Any `param('key')` in the query then resolves to nothing.

Since the `from` clause gained server-side parameter resolution, that has a visible consequence:

| | Search | Validate |
|---|---|---|
| `from param('EventStore')` with the param bound | resolves | **"No value supplied for the parameter used as the data source"** |

The same query text, the same server, opposite answers.

## Why it has not bitten yet

`validateQuery` is called from exactly one place — `QueryDocEditPresenter`, the editor for **Query**
documents. Query documents have no query variables, so nothing that calls the validator currently
writes `param()`.

The Floor Map, which does use `param('EventStore')` and `param('FactStore')`, uses
`QueryEditPresenter` instead, and that never calls the validator. So the two populations do not
overlap today. They will the moment anything parameterised is validated.

## Suggested fix

`validateQuery` takes a bare `String`. To carry parameters it needs a request object — the same
shape the search path already uses:

1. Change `QueryResource.validateQuery(String)` to take a small request carrying the query text and
   a `List<Param>` (or a whole `QuerySearchRequest`, which already has a `QueryContext`).
2. Have `QueryDocEditPresenter` pass whatever parameters it holds — none today, which is why this is
   safe to do without changing its behaviour.
3. Have `QueryEditPresenter` call the validator too, passing its query variables. It does not
   validate at all at present, so a Floor Map query with a typo is only reported when it runs.

Step 1 is the breaking part: it is a public REST signature on an upstream-owned resource, so it
wants either an upstream change or a versioned second endpoint rather than a silent edit.

## Verification

- A query using `param('X')` in its `from` clause validates successfully when the parameter is
  supplied, and fails with the unbound-parameter message when it is not.
- A query with no parameters validates exactly as it does today — the existing callers must not
  change behaviour.
- `QueryDocEditPresenter`'s two `validateQuery` call sites still work with an empty parameter list.
