# A failed histogram query is reported only to the browser console

**Component:** `stroom-core-client` — `HistogramQueryHelper`, and the Floor Map timeline that
consumes it
**Severity:** low. The information exists and is correct; it is written somewhere most users never
look, so in practice the timeline still empties without explanation.
**Status:** open. The error listener that produces the message was added deliberately; routing it
somewhere visible was not attempted.

---

## What happens now

`HistogramQueryHelper` registers a search-error listener and reports the first ERROR-severity
failure:

```java
Console.error("Histogram: the query failed, so the timeline shows no density bars."
              + " This is not the same as there being no data. Cause: " + ...);
```

That is a real improvement on the previous behaviour — every way this query can fail produces no
bars, which is also what an empty store produces, so the two were indistinguishable. But
`Console.error` ends at

```java
private static native void nativeConsoleLog(String s) /*-{ console.log( s ); }-*/;
```

so the message reaches the **browser devtools console** and nothing else. Two smaller points follow
from the same place: the level is a text prefix rather than a real severity, so it is emitted via
`console.log` and will not be filtered or styled as an error in devtools; and while it does fire in
production builds, that only matters to someone who already has devtools open.

## Why it is worth more than it sounds

The timeline's density bars are the only indication of *where in time* a floor map has data. When
they are empty the user's reasonable conclusion is "there is nothing here", and the two situations
that produce that are:

- the store genuinely holds nothing in this range — common, benign, and self-explanatory;
- the query is broken — an unresolved parameter, a missing timestamp column, an unreachable store.

The second is the one worth interrupting for, and it is currently the one that looks exactly like
the first.

## What the feature already does elsewhere

The Floor Map has a visible channel for precisely this class of problem. `FloorMapStageReporter`
classifies which stage of the pipeline came up empty, and `refreshEmptyStatus` writes a line of text
**on the canvas** — "No events at this time", "No floor plan at this time", and so on. So the
pattern, the wording discipline and the persistence rules already exist; what is missing is an
equivalent affordance on the timeline.

## Suggested fix

**Give the timeline a status line of its own**, mirroring the canvas one: a short piece of text
shown in place of the density bars when the histogram read fails, distinct from the bars simply
being flat.

That means a view change (`FloorMapTimelineViewImpl` has no text region today), a way for the helper
to report failure to its owner rather than to `Console` — most naturally the same
`Consumer`-shaped handler its results already use — and a decision about what the text says.

**A cheaper intermediate step**, if the view change is not wanted yet: have `HistogramQueryHelper`
take an error handler in its constructor rather than logging directly, so the Floor Map can word the
message and put it wherever it later decides. That is a small change and it stops the helper
deciding presentation for its caller.

## Verification

- With a deliberately broken events query (an unresolvable parameter, say), the timeline says the
  query failed rather than showing empty bars.
- With a genuinely empty store, it shows empty bars and says nothing — the two must not collapse
  into one message.
- The message appears once, and re-arms on document re-read, matching the existing `reset()`
  behaviour.
