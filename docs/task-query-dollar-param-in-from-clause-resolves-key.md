# `${param}` in a `from` clause resolves to the parameter's name, not its value

**Component:** `stroom-query-common` — `SearchRequestFactory.addDataSource`, with
`stroom-query-api`'s `ParamToken`
**Severity:** medium. It does not throw. It resolves the **wrong data source**, or none, and says
nothing — the failure mode is a query that returns no rows rather than one that reports a problem.
**Status:** open, and pre-existing. Left deliberately untouched by the change that made
`param('key')` resolve in a `from` clause, so that change altered nothing it did not intend to.

---

## What happens

Two parameter syntaxes reach the `from` clause, and they behave differently.

```
from param('EventStore')   -- resolves to the parameter's value
from ${EventStore}         -- resolves to the literal text "EventStore"
```

The second is the surprising one, and it is surprising because it looks like it works. The clause
checks `TokenType.isString(dataSourceToken)`, and `TokenType.ALL_STRINGS` includes `PARAM` — the
token type produced for `${…}` — so the token is accepted without complaint. The name then comes
from `dataSourceToken.getUnescapedText()`, and `ParamToken.build()` computes that as the text
**between `${` and `}`**:

```java
final String unescaped = string.substring(start, end);   // ParamToken.build()
```

That is the key, not the value. Nothing substitutes `${…}` in the query text on the search path:
`ParamUtil.replaceParameters` is never called on it, `KVMapUtil.replaceParameters` handles only
include/exclude filter patterns, and `ExpressionUtil.replaceExpressionParameters` runs later and
only over expression terms.

So `from ${EventStore}` looks for a data source literally named `EventStore`.

## Why it matters more than a wrong name usually would

Every other way of getting this wrong reports itself. A misspelled literal name fails to resolve and
the user is told. An unbound `param('key')` now throws
*"No value supplied for the parameter used as the data source"*. This one produces a name that is
perfectly well-formed and simply refers to something else — so the outcome depends on whether a
document of that name happens to exist:

- **usually** — no such data source, and the query fails with a message naming a store the user
  never typed;
- **occasionally** — a document *is* called `EventStore`, and the query silently reads the wrong
  data.

## Suggested fix

In the same place that now resolves `param('key')`, resolve a `PARAM` token through `paramMap`:

```java
if (TokenType.PARAM.equals(dataSourceToken.getTokenType())) {
    final String value = paramMap.get(dataSourceToken.getUnescapedText());
    if (value == null) {
        throw new TokenException(dataSourceToken, "No value supplied for the parameter used as the data source");
    }
    return value;
}
```

The awkward question is what to do about anyone relying on the present behaviour — a data source
genuinely named `EventStore` reached by writing `${EventStore}`. That seems vanishingly unlikely and
is not worth a compatibility switch, but it is the reason this is a behaviour change rather than a
pure bug fix, and it should be called out in the change notes.

**Worth deciding at the same time:** whether `${…}` should be supported in a `from` clause at all,
or rejected with a message pointing at `param('…')`. Two syntaxes that mean the same thing in one
position is not obviously better than one that works and one that is refused.

## Verification

`TestSearchRequestFactoryDataSourceParam.dollarBraceParamStillResolvesToTheKeyNameNotItsValue`
pins the current behaviour. It is written so that fixing this makes it fail, and its javadoc says
the test should then be rewritten to assert the value rather than deleted.

Add alongside it: an unbound `${…}` throws rather than resolving to the key, and a `from` clause
using `param('…')` is unaffected.
