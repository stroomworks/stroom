/*
 * Copyright 2025 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pathways.impl;

import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.AbstractRange;
import stroom.pathways.shared.pathway.AbstractSet;
import stroom.pathways.shared.pathway.AbstractValue;
import stroom.pathways.shared.pathway.AnyBoolean;
import stroom.pathways.shared.pathway.AnyTypeValue;
import stroom.pathways.shared.pathway.BooleanValue;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.IntegerRange;
import stroom.pathways.shared.pathway.IntegerSet;
import stroom.pathways.shared.pathway.IntegerValue;
import stroom.pathways.shared.pathway.LongRange;
import stroom.pathways.shared.pathway.LongSet;
import stroom.pathways.shared.pathway.LongValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.NanoTimeValue;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.Regex;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;
import stroom.planb.impl.dao.trace.CanonicalSpanOrder;
import stroom.planb.impl.dao.trace.CanonicalSpanOrder.SpanGroup;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class NodeMutatorImpl {

    private static final int MAX_SET_SIZE = 10;
    // The longest run of children looked at when saying a repeat once. A repeating group longer than
    // this is not one a reader would follow as a repeat.
    private static final int MAX_GROUP = 64;
    private static final String ATTRIBUTE_PREFIX = "attribute.";
    private static final String OCCURRENCES = "occurrences";

    private final CanonicalSpanOrder spanOrder;
    private final IgnoredAttributes ignoredAttributes;
    private final IgnoredSpans ignoredSpans;

    // What this trace taught the model: a node it had not seen, or a constraint it had to add or
    // widen. One of these is made per trace, so the list covers that trace and no other. Kept in the
    // order the changes happened so a replay can follow them.
    private final List<PathwayMutation> mutations = new ArrayList<>();


    // The shape of what each span did, by the span it was folded from. Held while the trace is walked
    // because a span's shape is only complete once its children have been, and the walk reaches those
    // grouped by name rather than in the order they ran.
    private final Map<String, RouteShape> shapeBySpan = new HashMap<>();
    private String rootSpanId;

    // Which trace and span the change being recorded came from. Fields because the methods that
    // notice a constraint moving are several calls below the one that knows. Which node it happened to
    // is passed instead, so a constraint is never recorded against the wrong one.
    private NanoTime time;
    private String traceId;
    private String spanId;
    // Whether what is being recorded is a node this trace did not carry.
    private boolean absent;

    public NodeMutatorImpl(final CanonicalSpanOrder spanOrder,
                           final IgnoredAttributes ignoredAttributes,
                           final IgnoredSpans ignoredSpans) {
        this.spanOrder = spanOrder;
        this.ignoredAttributes = ignoredAttributes;
        this.ignoredSpans = ignoredSpans;
    }

    /**
     * Every change the trace just folded in made to the model, in the order it made them. Empty where
     * it took a route the model already knew in a way it already allowed, which is the normal case
     * once a pathway has settled.
     */
    public List<PathwayMutation> getMutations() {
        return mutations;
    }


    /**
     * What the trace did, as one shape holding the whole walk: every node it reached, the children it
     * ran at each, and the order it ran them in. Null where no trace has been folded in.
     */
    public RouteShape getRouteShape() {
        return shapeBySpan.get(rootSpanId);
    }


    /**
     * Whether the trace taught the model anything. The same question as whether it made any changes.
     */
    public boolean isChanged() {
        return !mutations.isEmpty();
    }

    private void record(final PathNode node,
                        final MutationType type0,
                        final String constraint,
                        final boolean optional,
                        final ConstraintValue oldValue,
                        final ConstraintValue newValue) {
        // A node the trace did not carry is not the node changing the way the others here are, so it
        // is told apart rather than looking like any other widening.
        final MutationType type = absent
                ? MutationType.NODE_ABSENT
                : type0;
        // Numbered when written, because where it sits in the pathway's history is not known here.
        mutations.add(new PathwayMutation(0L, time, traceId, spanId, node.getPath(), node.getUuid(),
                constraint, type, optional, oldValue, newValue));
    }

    // A change to the node rather than to one of its constraints, so there is no flag to carry.
    private void record(final PathNode node, final MutationType type) {
        record(node, type, null, false, null, null);
    }


    public PathNode process(final Trace trace,
                            final PathKey pathKey,
                            final PathNode pathNode,
                            final MessageReceiver messageReceiver,
                            final PathwaysDoc pathwaysDoc) {
        final Span root = trace.root();
        time = NanoTimeUtil.fromInstant(Instant.now());
        traceId = trace.getTraceId();
        spanId = root.getSpanId();
        rootSpanId = root.getSpanId();
        final MessageReceiver messages =
                MessageReceiver.forSpan(messageReceiver, traceId, spanId);
        if (pathNode == null && !pathwaysDoc.isAllowPathwayCreation()) {
            messages.log(Severity.ERROR, () -> "Invalid path: " + pathKey);
            return pathNode;
        }

        final PathNode node;
        if (pathNode == null) {
            messages.log(Severity.INFO, () -> "Adding new root path: " + root.getName());
            node = new PathNode(root.getName());
            // Named like any other node, so a replay running forwards can put the root back as it was.
            // That this was the pathway coming into being rather than a node appearing beneath one is
            // what the type says.
            record(node, MutationType.PATHWAY_ADDED);
        } else {
            node = pathNode;
        }

        return walk(trace, root, node, messageReceiver, pathwaysDoc);
    }

    private PathNode walk(final Trace trace,
                          final Span parentSpan,
                          final PathNode parentNode,
                          final MessageReceiver messageReceiver,
                          final PathwaysDoc pathwaysDoc) {
        // Everything below names the span being folded in. The recursive call below is given the
        // receiver this one was given, not this one's, so each level puts its own span on the front
        // rather than stacking them up.
        spanId = parentSpan.getSpanId();
        final MessageReceiver messages =
                MessageReceiver.forSpan(messageReceiver, traceId, spanId);

        final List<SpanGroup> groups = spanOrder.groups(trace.children(parentSpan));
        final List<Span> ordered = new ArrayList<>();
        groups.forEach(group -> group.runs().forEach(ordered::addAll));

        // This trace's children grouped by name, first appearance first. The same name twice is one
        // child that happened twice, not two children — what a child is does not change because it ran
        // again, so every span of a name is folded into the one node.
        final Map<String, List<Span>> spansByName = new LinkedHashMap<>();
        ordered.forEach(span -> spansByName
                .computeIfAbsent(span.getName(), k -> new ArrayList<>())
                .add(span));

        // The children this node ran, named in the sequence they were reached, with work done over and
        // over said once. A run of the same child is one step, and so is a run of the same group of
        // children: reading twenty documents a page at a time is the amount of work there was to do,
        // not a different way of working, and how many times each child ran is counted on the child.
        // A child that comes back after another has run is a step of its own, because it did not
        // happen where the first one happened — running a then b then a is not running a twice and
        // then b, and a model that could not tell the two apart said the second was the first.
        //
        final PathNode.Builder pathNodeBuilder =
                addConstraints(parentNode, parentSpan, messages, pathwaysDoc);

        final Map<String, PathNode> existing = new LinkedHashMap<>();
        NullSafe.list(parentNode.getChildren()).forEach(child -> existing.put(child.getName(), child));

        // Every name the model knows, in the order it already holds them, then any this trace brought
        // that it did not. A name keeps the place it was first given, so the stored order is the order
        // the steps were first reached and it does not shuffle between writes.
        final Set<String> names = new LinkedHashSet<>(existing.keySet());
        names.addAll(spansByName.keySet());

        final List<PathNode> children = new ArrayList<>(names.size());
        for (final String name : names) {
            final List<Span> spans = spansByName.get(name);
            PathNode child = existing.get(name);

            if (child == null) {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messages.log(Severity.ERROR, () ->
                            "Invalid path: " + parentNode.getPath() + " " + name);
                    continue;
                }
                final List<String> path = new ArrayList<>(parentNode.getPath());
                path.add(name);
                messages.log(Severity.INFO, () -> "Adding new path: " + path);
                child = new PathNode(name, path);
                record(child, MutationType.NODE_ADDED);
            }

            // Fold every span of this name into the one child, then record how many there were. A
            // child the model knows about that this trace did not carry happened no times.
            if (spans != null) {
                for (final Span span : spans) {
                    child = walk(trace, span, child, messageReceiver, pathwaysDoc);
                }
                // The recursion moved the span on; put it back for what this level does next.
                spanId = parentSpan.getSpanId();
            }
            children.add(withCount(child,
                    spans == null
                            ? 0
                            : spans.size(),
                    messages,
                    pathwaysDoc));
        }

        // Read in the order the work happened rather than the order the model holds the names, so the
        // shape says what the trace did. The children loop above has put each span's shape in hand.
        final List<RouteShape> ran = new ArrayList<>(ordered.size());
        for (final SpanGroup group : groups) {
            final List<RouteShape> runs = new ArrayList<>(group.runs().size());
            for (final List<Span> run : group.runs()) {
                final List<RouteShape> shapes = shapesOf(run);
                if (!shapes.isEmpty()) {
                    runs.add(RouteShape.run(collapse(sameNodeRunsInOrder(shapes))));
                }
            }
            if (runs.size() == 1) {
                // One thread had the moment to itself, so there is nothing to say about order beyond
                // what it did.
                ran.addAll(runs.getFirst().steps());
            } else if (!runs.isEmpty()) {
                // Read in an order settled by what the runs are rather than by which thread got there
                // first, so the same work on four threads is the same route however they were timed.
                // Sorting also brings runs that did the same thing together, and the collapse then
                // says them once: how many threads there were is how much work there was to do.
                //
                // What is left is held as work that happened at the same time even where it came down
                // to a single run, because that is the thing worth saying about it. Only a moment one
                // run had to itself, handled above, is read as a plain sequence.
                runs.sort(NodeMutatorImpl::compare);
                ran.add(RouteShape.concurrent(mergeTurnsOfOneNode(collapse(runs))));
            }
        }
        shapeBySpan.put(parentSpan.getSpanId(),
                RouteShape.of(parentNode.getUuid(), collapse(sameNodeRunsInOrder(ran))));

        pathNodeBuilder.children(children);
        return pathNodeBuilder.build();
    }

    // The shapes of the spans given, leaving out any span that is not part of the route. What such a
    // span ran goes with it, because its steps are held in the shape being dropped — a step that is
    // not on the route cannot have steps of its own that are.
    private List<RouteShape> shapesOf(final List<Span> spans) {
        final List<RouteShape> shapes = new ArrayList<>(spans.size());
        for (final Span span : spans) {
            if (!ignoredSpans.isEmpty() && ignoredSpans.test(span.getName())) {
                continue;
            }
            final RouteShape shape = shapeBySpan.get(span.getSpanId());
            if (shape != null) {
                shapes.add(shape);
            }
        }
        return shapes;
    }

    // Runs that hold nothing but turns of one node, said as a single run of the turns they took
    // between them; the runs left alone where they hold anything else.
    //
    // Where several threads take turns of the same node off a shared queue, which thread takes which
    // turn is decided by what it reached first. A thread that took a turn the others did not is then
    // a run apart, and the route records which thread that was — a fact about the workload that
    // changes every time the job runs. Saying the turns between them keeps what was done and drops
    // who did it; how many threads there were is already counted on the node.
    //
    // Threads doing different work keep their runs, because doing different things is what the runs
    // are there to say.
    private static List<RouteShape> mergeTurnsOfOneNode(final List<RouteShape> runs) {
        if (runs.size() < 2) {
            return runs;
        }
        // Asked of each run on its own rather than of the group, so a thread that was doing something
        // else at the time is held apart without stopping the rest being said between them. One
        // unrelated query running alongside otherwise left the whole group as one run per thread,
        // which is the share-out the runs are there to drop.
        final Map<String, List<RouteShape>> byNode = new LinkedHashMap<>();
        final List<RouteShape> kept = new ArrayList<>();
        for (final RouteShape run : runs) {
            final String node = soleNodeOf(run);
            if (node == null) {
                kept.add(run);
            } else {
                byNode.computeIfAbsent(node, k -> new ArrayList<>()).addAll(run.steps());
            }
        }
        if (byNode.isEmpty()) {
            return runs;
        }
        byNode.values().forEach(turns ->
                kept.add(RouteShape.run(collapse(sameNodeRunsInOrder(turns)))));
        kept.sort(NodeMutatorImpl::compare);
        return kept;
    }

    // The node every turn of this run reached, or null where the run holds anything else — a turn of
    // another node, or a shape that is not a node at all.
    private static String soleNodeOf(final RouteShape run) {
        String node = null;
        for (final RouteShape turn : run.steps()) {
            if (turn.nodeUuid() == null) {
                return null;
            }
            if (node == null) {
                node = turn.nodeUuid();
            } else if (!node.equals(turn.nodeUuid())) {
                return null;
            }
        }
        return node;
    }

    // One node's own turns, put in an order that does not depend on which of them ran first.
    //
    // A node reached several times in a row can behave differently each time, and which turn behaved
    // which way is not the path through the code: where the turns run in parallel it is decided by
    // which thread read the clock first, and where they do not it is decided by what was queued. Two
    // traces where the same node ran both ways took the same route, so the turns are read in an order
    // settled by what they are rather than by when they happened.
    //
    // Only turns of the same node next to each other, so a node that came back after another one ran
    // is left where it is: running a then b then a is not running a twice and then b.
    private static List<RouteShape> sameNodeRunsInOrder(final List<RouteShape> steps) {
        final List<RouteShape> out = new ArrayList<>(steps);
        int i = 0;
        while (i < out.size()) {
            final String node = out.get(i).nodeUuid();
            int end = i + 1;
            while (node != null && end < out.size() && node.equals(out.get(end).nodeUuid())) {
                end++;
            }
            if (end - i > 1) {
                out.subList(i, end).sort(NodeMutatorImpl::compare);
            }
            i = end;
        }
        return out;
    }

    // Any order at all, so long as the same shapes always come out the same way round.
    private static int compare(final RouteShape a, final RouteShape b) {
        if (a.steps().size() != b.steps().size()) {
            return Integer.compare(a.steps().size(), b.steps().size());
        }
        for (int i = 0; i < a.steps().size(); i++) {
            final int step = compare(a.steps().get(i), b.steps().get(i));
            if (step != 0) {
                return step;
            }
        }
        if (a.kind() != b.kind()) {
            return a.kind().compareTo(b.kind());
        }
        return NullSafe.string(a.nodeUuid()).compareTo(NullSafe.string(b.nodeUuid()));
    }

    // The children run here in order, with work that repeated said once.
    //
    // The unit is found first and the list is then read at that length, rather than every length from
    // one upwards being tried against a grid fixed to the start of the list. That is what lets a run
    // whose turn stopped early be told from a clean one wherever in the run it happened, rather than
    // only at the end, and what stops a doubled child being eaten before the round it belongs to is
    // looked at.
    private static List<RouteShape> collapse(final List<RouteShape> steps) {
        final List<RouteShape> out = new ArrayList<>(steps.size());
        int i = 0;
        while (i < steps.size()) {
            final int unit = bestUnit(steps, i);
            if (unit == 0) {
                out.add(steps.get(i));
                i++;
            } else {
                final List<RouteShape> group = new ArrayList<>(steps.subList(i, i + unit));
                int next = i + unit;
                boolean partial = false;
                while (next < steps.size()) {
                    if (matches(steps, next, group)) {
                        next += unit;
                        continue;
                    }
                    final int part = partialRun(steps, next, group);
                    if (part > 0) {
                        partial = true;
                        next += part;
                        continue;
                    }
                    break;
                }
                // The unit is read the same way, so a repeat inside a repeat comes out once rather
                // than several times. It is shorter than what it came from, so this ends.
                final List<RouteShape> said = collapse(group);
                if (partial) {
                    out.add(RouteShape.unfinished(said));
                } else {
                    // Said once, with nothing to mark that it happened again. How much work there was
                    // to do is the workload rather than the path through the code, so a turn that ran
                    // once and a turn that ran twenty times read the same.
                    out.addAll(said);
                }
                i = next;
            }
        }
        return out;
    }

    // How long a run has to be for the list from here to be that run happening again, taking the
    // length that covers the most; zero where nothing from here repeats.
    private static int bestUnit(final List<RouteShape> steps, final int from) {
        int best = 0;
        int bestCover = 0;
        for (int length = 1; length <= MAX_GROUP && from + 2 * length <= steps.size(); length++) {
            final List<RouteShape> unit = steps.subList(from, from + length);
            int runs = 1;
            while (matches(steps, from + runs * length, unit)) {
                runs++;
            }
            if (runs > 1 && runs * length > bestCover) {
                best = length;
                bestCover = runs * length;
            }
        }
        return best;
    }

    private static boolean matches(final List<RouteShape> steps, final int at, final List<RouteShape> unit) {
        return at + unit.size() <= steps.size()
               && steps.subList(at, at + unit.size()).equals(unit);
    }

    // How much of the unit ran here, where it started and did not finish; zero where no part of it
    // did. Only counted where the work picks the unit up again straight after, or there is nothing
    // after it at all — a step that merely starts the same way as the unit but is followed by
    // something else is its own step, not an unfinished turn of this one.
    //
    // Longest first, because a shorter reading can satisfy that test where the longest does not: a
    // turn of "a b P d" that stopped after "a b" is followed by another turn beginning "a b", so
    // matching as far as the names go would run past where the turn actually ended.
    private static int partialRun(final List<RouteShape> steps,
                                  final int at,
                                  final List<RouteShape> unit) {
        int longest = 0;
        while (longest < unit.size() - 1
               && at + longest < steps.size()
               && steps.get(at + longest).equals(unit.get(longest))) {
            longest++;
        }
        for (int length = longest; length > 0; length--) {
            if (at + length == steps.size() || matches(steps, at + length, unit)) {
                return length;
            }
        }
        return 0;
    }

    private PathNode withCount(final PathNode pathNode,
                               final int count,
                               final MessageReceiver messageReceiver,
                               final PathwaysDoc pathwaysDoc) {
        final Map<String, Constraint> constraints = pathNode.getConstraints() == null
                ? new HashMap<>()
                : new HashMap<>(pathNode.getConstraints());

        // A node this trace did not carry has no span of its own, so nothing here may claim one. What
        // is put back afterwards is the span whose children are being counted, which is the parent's.
        final String was = spanId;
        if (count == 0) {
            spanId = null;
            absent = true;
        }
        try {
            setOrExpand(constraints, pathNode, OCCURRENCES, count, false, messageReceiver,
                    pathwaysDoc);
        } finally {
            spanId = was;
            absent = false;
        }
        return pathNode.copy().constraints(constraints).build();
    }

    private PathNode.Builder addConstraints(final PathNode pathNode,
                                            final Span span,
                                            final MessageReceiver messageReceiver,
                                            final PathwaysDoc pathwaysDoc) {
        // This runs once for every span folded into the node, so it counts spans rather than traces.
        final PathNode.Builder pathNodeBuilder = pathNode.copy()
                .timesUsed(pathNode.getTimesUsed() + 1)
                .lastUsedTime(time);
//        // Add additional span info if wanted.
//        final List<Span> spans;
//        if (pathNode.getSpans() != null) {
//            spans = new ArrayList<>(pathNode.getSpans());
//            spans.add(span);
//        } else {
//            spans = Collections.singletonList(span);
//        }
//        pathNodeBuilder.spans(spans);

        // TODO : Expand min/max/average execution times.
        final Map<String, Constraint> constraints;
        final boolean optional;
        if (pathNode.getConstraints() != null) {
            constraints = pathNode.getConstraints();
            // We already have some constraints so make any new constraints optional.
            optional = true;
        } else {
            constraints = new HashMap<>();
            // These are new constraints so all initial ones will be set to be required.
            optional = false;
        }

        // Set or expand duration range.
        final NanoTime startTime = NanoTime.fromString(span.getStartTimeUnixNano());
        final NanoTime endTime = NanoTime.fromString(span.getEndTimeUnixNano());
        final NanoTime duration = endTime.subtract(startTime);

        setOrExpand(constraints, pathNode, "duration", duration, false, messageReceiver, pathwaysDoc);

        // Set or expand flags.
        setOrExpand(constraints, pathNode, "flags", span.getFlags(), false, messageReceiver, pathwaysDoc);

        // Set or expand kind.
        setOrExpand(constraints, pathNode, "kind", span.getKind().name(), false, messageReceiver, pathwaysDoc);

        // Create attribute sets. A span can legitimately carry no attributes at all, and then arrives
        // with a null list rather than an empty one.
        // A span may carry the same key twice: the wire format allows it and nothing on the way in
        // deduplicates. Last one wins, as it does in the OTel SDKs — the alternative, which is what
        // Collectors.toMap does without a merge function, is to throw and lose the whole trace.
        final Map<String, KeyValue> attributes = NullSafe.list(span.getAttributes())
                .stream()
                .collect(Collectors.toMap(kv -> ATTRIBUTE_PREFIX + kv.getKey(), Function.identity(),
                        (first, second) -> second));

        // Make required constraints optional if they don't exist in this set.
        final Map<String, Constraint> newConstraints = new HashMap<>(constraints.size());
        constraints.forEach((key, value) -> {
            if (!attributes.containsKey(key) && !value.isOptional() && key.startsWith(ATTRIBUTE_PREFIX)) {
                if (!pathwaysDoc.isAllowConstraintMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Attribute required: " + pathNode.getPath() + " " + key);
                } else {
                    messageReceiver.log(Severity.INFO, () -> "Making constraint optional: " +
                                                             pathNode.getPath() + " " +
                                                             key);
                    record(pathNode, MutationType.CONSTRAINT_OPTIONAL, key, true, value.getValue(),
                            value.getValue());
                    newConstraints.put(key, value.copy().optional(true).build());
                }
            } else {
                newConstraints.put(key, value);
            }
        });

        // Set or expand attributes. One the configuration says to ignore is recorded as accepting
        // anything, once, and then left alone — it stays visible against the node without its value
        // being learnt or widened every time a trace carries a different one.
        attributes.forEach((key, value) -> {
            if (!ignoredAttributes.isEmpty()
                && ignoredAttributes.test(key.substring(ATTRIBUTE_PREFIX.length()))) {
                final Constraint was = newConstraints.get(key);
                if (!(NullSafe.get(was, Constraint::getValue) instanceof AnyTypeValue)) {
                    // Not through put(): AnyTypeValue defines no equals, so every trace would look
                    // like a change. Recorded once, and thereafter only the count moves.
                    record(pathNode, MutationType.CONSTRAINT_IGNORED, key, optional,
                            NullSafe.get(was, Constraint::getValue), new AnyTypeValue());
                }
                // The span carried the attribute whether or not its value is being learnt, so it
                // counts as a use like any other.
                newConstraints.put(key, new Constraint(key, new AnyTypeValue(), optional,
                        was == null
                                ? 1L
                                : was.getTimesUsed() + 1,
                        time));
            } else {
                setOrExpand(newConstraints, pathNode, key, value.getValue(), optional,
                        messageReceiver, pathwaysDoc);
            }
        });

        pathNodeBuilder.constraints(newConstraints);
        return pathNodeBuilder;
    }

    private void setOrExpand(final Map<String, Constraint> constraints,
                             final PathNode pathNode,
                             final String name,
                             final Object value,
                             final boolean optional,
                             final MessageReceiver messageReceiver,
                             final PathwaysDoc pathwaysDoc) {
        final Supplier<String> location = () -> pathNode.getPath() + " " + name;
        final Constraint constraint = constraints.get(name);

        if (value == null) {
            if (!optional) {
                messageReceiver.log(Severity.ERROR, () ->
                        "Null value for: " + location.get());
            }
        } else {
            if (constraint == null && !pathwaysDoc.isAllowConstraintCreation()) {
                messageReceiver.log(Severity.ERROR, () ->
                        "Constraint not found: " + location.get() + " " + value);

            } else {
                final boolean opt = NullSafe.getOrElse(constraint, Constraint::isOptional, optional);
                switch (value) {
                    case final Integer val -> put(constraints, pathNode, name,
                            createIntConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final Long val -> put(constraints, pathNode, name,
                            createLongConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final Boolean val -> put(constraints, pathNode, name,
                            createBooleanConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final String val -> put(constraints, pathNode, name,
                            createStringConstraint(location,
                                    name,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final NanoTime val -> put(constraints, pathNode, name,
                            createNanoTimeConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final AnyValue val -> {
                        // Unwrap.
                        if (val.getStringValue() != null) {
                            setOrExpand(constraints,
                                    pathNode,
                                    name,
                                    val.getStringValue(),
                                    optional,
                                    messageReceiver,
                                    pathwaysDoc);
                        } else if (val.getBoolValue() != null) {
                            setOrExpand(constraints,
                                    pathNode,
                                    name,
                                    val.getBoolValue(),
                                    optional,
                                    messageReceiver,
                                    pathwaysDoc);
                        } else if (val.getIntValue() != null) {
                            setOrExpand(constraints,
                                    pathNode,
                                    name,
                                    val.getIntValue(),
                                    optional,
                                    messageReceiver,
                                    pathwaysDoc);
                        }

                        // TODO : Add constraints for other attribute types.
                    }
                    default -> {
                    }
                }
            }
        }
    }

    // Records the constraint, noticing whether it is really different from the one already there.
    // createXConstraint hands the value straight back when the trace is within what the model already
    // allows, and that must not count as the model having moved.
    private void put(final Map<String, Constraint> constraints,
                     final PathNode pathNode,
                     final String name,
                     final ConstraintValue value,
                     final boolean optional) {
        final Constraint existing = constraints.get(name);
        if (existing == null) {
            record(pathNode, MutationType.CONSTRAINT_ADDED, name, optional, null, value);
        } else if (existing.isOptional() != optional || !Objects.equals(existing.getValue(), value)) {
            record(pathNode, widening(existing.getValue(), value), name, optional, existing.getValue(),
                    value);
        }
        // Counted whether or not the value moved: this is how many values the constraint has been
        // given, which is a different question from how often it has changed.
        constraints.put(name, new Constraint(name, value, optional,
                existing == null
                        ? 1L
                        : existing.getTimesUsed() + 1,
                time));
    }

    // How a constraint loosened, worked out from the pair of values rather than passed down from the
    // place that loosened it, so the five constraint builders stay unaware of it. A trace carries one
    // value, so a range can only push out one end at a time.
    private static MutationType widening(final ConstraintValue was, final ConstraintValue now) {
        // Anything reaching here already admits everything, so the constraint has stopped checking.
        // The one configured to do so never comes through here, so this is a value whose type did not
        // match the ones before it.
        if (now instanceof AnyTypeValue) {
            return MutationType.CONSTRAINT_TYPE_CONFLICT;
        }
        // Regex is a value, so this has to come before any test for one.
        if (now instanceof Regex || now instanceof AnyBoolean) {
            return MutationType.CONSTRAINT_GENERALISED;
        }

        if (now instanceof final AbstractRange<?> to) {
            if (was instanceof AbstractSet<?>) {
                return MutationType.CONSTRAINT_RANGED;
            }
            if (was instanceof final AbstractRange<?> from) {
                if (!Objects.equals(from.getMin(), to.getMin())) {
                    return MutationType.CONSTRAINT_MIN_EXPANDED;
                }
                if (!Objects.equals(from.getMax(), to.getMax())) {
                    return MutationType.CONSTRAINT_MAX_EXPANDED;
                }
            } else if (was instanceof final AbstractValue<?> from) {
                // The one value seen so far becomes one end of the range; the other end is the new one.
                return Objects.equals(to.getMin(), from.getValue())
                        ? MutationType.CONSTRAINT_MAX_EXPANDED
                        : MutationType.CONSTRAINT_MIN_EXPANDED;
            }
        }

        if (now instanceof AbstractSet<?>) {
            return MutationType.CONSTRAINT_SET_EXPANDED;
        }
        return MutationType.CONSTRAINT_CHANGED;
    }

    private ConstraintValue getConstraintValue(final Constraint constraint) {
        if (constraint == null) {
            return null;
        }
        return constraint.getValue();
    }

    private ConstraintValue createNanoTimeConstraint(final Supplier<String> location,
                                                     final ConstraintValue current,
                                                     final NanoTime value,
                                                     final MessageReceiver messageReceiver,
                                                     final PathwaysDoc pathwaysDoc) {
        if (current == null) {
            if (!pathwaysDoc.isAllowPathwayMutation()) {
                messageReceiver.log(Severity.ERROR, () ->
                        "Unexpected time: " + location.get() + " " + value);
            } else {
                messageReceiver.log(Severity.INFO, () ->
                        "Adding time constraint: " + location.get() + " " + value);
                return new NanoTimeValue(value);
            }
        } else if (current instanceof final NanoTimeValue nanoTimeValue) {
            if (!Objects.equals(nanoTimeValue.getValue(), value)) {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Unexpected time: " + location.get() + " " + value);
                } else {
                    if (nanoTimeValue.getValue().isGreaterThan(value)) {
                        // Smaller than the one time seen so far, so it becomes the bottom of a range.
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding min time constraint: " + location.get() + " " + value);
                        return new NanoTimeRange(value, nanoTimeValue.getValue());
                    } else if (nanoTimeValue.getValue().isLessThan(value)) {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding max time constraint: " + location.get() + " " + value);
                        return new NanoTimeRange(nanoTimeValue.getValue(), value);
                    }
                }
            }
//        } else if (current instanceof final IntegerSet intSet) {
//            final Set<Integer> set = new HashSet<>(intSet.getSet());
//            set.add(value);
//
//            if (set.size() > MAX_SET_SIZE) {
//                // Convert to range.
//                int min = value;
//                int max = value;
//                for (final int num : intSet.getSet()) {
//                    min = Math.min(min, num);
//                    max = Math.max(max, num);
//                }
//                return new IntegerRange(min, max);
//            } else {
//                return new IntegerSet(set);
//            }
        } else if (current instanceof final NanoTimeRange timeRange) {
            if (timeRange.getMin().isGreaterThan(value)) {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Time exceeds min constraint: " + location.get() + " " + value);
                } else {
                    messageReceiver.log(Severity.INFO, () ->
                            "Expanding min time constraint: " + location.get() + " " + value);
                    return new NanoTimeRange(value, timeRange.getMax());
                }
            } else if (timeRange.getMax().isLessThan(value)) {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Time exceeds max constraint: " + location.get() + " " + value);
                } else {
                    messageReceiver.log(Severity.INFO, () ->
                            "Expanding max time constraint: " + location.get() + " " + value);
                    return new NanoTimeRange(timeRange.getMin(), value);
                }
            }
        } else if (!(current instanceof AnyTypeValue)) {
            if (!pathwaysDoc.isAllowPathwayMutation()) {
                messageReceiver.log(Severity.ERROR, () ->
                        "Unexpected type found: " + location.get() + " " + value);
            } else {
                messageReceiver.log(Severity.WARNING, () ->
                        "Changing to any type: " + location.get() + " " + value);
                return new AnyTypeValue();
            }
        }
        return current;
    }

    private ConstraintValue createIntConstraint(final Supplier<String> location,
                                                final ConstraintValue current,
                                                final int value,
                                                final MessageReceiver messageReceiver,
                                                final PathwaysDoc pathwaysDoc) {
        switch (current) {
            case null -> {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Unexpected integer: " + location.get() + " " + value);
                } else {
                    messageReceiver.log(Severity.INFO, () ->
                            "Adding integer constraint: " + location.get() + " " + value);
                    return new IntegerValue(value);
                }
            }
            case final IntegerValue intValue -> {
                if (!Objects.equals(intValue.getValue(), value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected integer: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding integer set: " + location.get() + " " + value);
                        return new IntegerSet(Set.of(intValue.getValue(), value));
                    }
                }
            }
            case final IntegerSet intSet -> {
                final Set<Integer> set = new HashSet<>(intSet.getSet());
                if (set.add(value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected integer: " + location.get() + " " + value);
                    } else {
                        if (set.size() > MAX_SET_SIZE) {
                            // Convert to range.
                            int min = value;
                            int max = value;
                            for (final int num : intSet.getSet()) {
                                min = Math.min(min, num);
                                max = Math.max(max, num);
                            }
                            messageReceiver.log(Severity.INFO, () ->
                                    "Making integer range: " + location.get() + " " + value);
                            return new IntegerRange(min, max);
                        } else {
                            messageReceiver.log(Severity.INFO, () ->
                                    "Expanding integer set: " + location.get() + " " + value);
                            return new IntegerSet(set);
                        }
                    }
                }
            }
            case final IntegerRange intRange -> {
                if (intRange.getMin() > value) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Integer exceeds min constraint: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding integer range min: " + location.get() + " " + value);
                        return new IntegerRange(value, intRange.getMax());
                    }
                } else if (intRange.getMax() < value) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Integer exceeds max constraint: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding integer range max: " + location.get() + " " + value);
                        return new IntegerRange(intRange.getMin(), value);
                    }
                }
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected type found: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.WARNING, () ->
                                "Changing to any type: " + location.get() + " " + value);
                        return new AnyTypeValue();
                    }
                }
            }
        }
        return current;
    }

    private ConstraintValue createLongConstraint(final Supplier<String> location,
                                                final ConstraintValue current,
                                                final long value,
                                                final MessageReceiver messageReceiver,
                                                final PathwaysDoc pathwaysDoc) {
        switch (current) {
            case null -> {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Unexpected long: " + location.get() + " " + value);
                } else {
                    messageReceiver.log(Severity.INFO, () ->
                            "Adding integer constraint: " + location.get() + " " + value);
                    return new LongValue(value);
                }
            }
            case final LongValue longValue -> {
                if (!Objects.equals(longValue.getValue(), value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected long: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding long set: " + location.get() + " " + value);
                        return new LongSet(Set.of(longValue.getValue(), value));
                    }
                }
            }
            case final LongSet longSet -> {
                final Set<Long> set = new HashSet<>(longSet.getSet());
                if (set.add(value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected long: " + location.get() + " " + value);
                    } else {
                        if (set.size() > MAX_SET_SIZE) {
                            // Convert to range.
                            long min = value;
                            long max = value;
                            for (final long num : longSet.getSet()) {
                                min = Math.min(min, num);
                                max = Math.max(max, num);
                            }
                            messageReceiver.log(Severity.INFO, () ->
                                    "Making long range: " + location.get() + " " + value);
                            return new LongRange(min, max);
                        } else {
                            messageReceiver.log(Severity.INFO, () ->
                                    "Expanding long set: " + location.get() + " " + value);
                            return new LongSet(set);
                        }
                    }
                }
            }
            case final LongRange longRange -> {
                if (longRange.getMin() > value) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Long exceeds min constraint: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding long range min: " + location.get() + " " + value);
                        return new LongRange(value, longRange.getMax());
                    }
                } else if (longRange.getMax() < value) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Long exceeds max constraint: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding long range max: " + location.get() + " " + value);
                        return new LongRange(longRange.getMin(), value);
                    }
                }
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected type found: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.WARNING, () ->
                                "Changing to any type: " + location.get() + " " + value);
                        return new AnyTypeValue();
                    }
                }
            }
        }
        return current;
    }

    private ConstraintValue createBooleanConstraint(final Supplier<String> location,
                                                    final ConstraintValue current,
                                                    final boolean value,
                                                    final MessageReceiver messageReceiver,
                                                    final PathwaysDoc pathwaysDoc) {
        switch (current) {
            case null -> {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Unexpected boolean: " + location.get() + " " + value);
                } else {
                    messageReceiver.log(Severity.INFO, () ->
                            "Adding boolean constraint: " + location.get() + " " + value);
                    return new BooleanValue(value);
                }
            }
            case final BooleanValue booleanValue -> {
                if (!Objects.equals(booleanValue.getValue(), value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected boolean: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding to any boolean: " + location.get() + " " + value);
                        return new AnyBoolean();
                    }
                }
            }
            case final AnyBoolean booleanValue -> {
                // Do nothing.
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected type found: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.WARNING, () ->
                                "Changing to any type: " + location.get() + " " + value);
                        return new AnyTypeValue();
                    }
                }
            }
        }
        return current;
    }

    private ConstraintValue createStringConstraint(final Supplier<String> location,
                                                   final String name,
                                                   final ConstraintValue current,
                                                   final String value,
                                                   final MessageReceiver messageReceiver,
                                                   final PathwaysDoc pathwaysDoc) {
        switch (current) {
            case null -> {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Unexpected string: " + location.get() + " " + value);
                } else {
                    messageReceiver.log(Severity.INFO, () ->
                            "Adding string constraint: " + location.get() + " " + value);
                    return new StringValue(value);
                }
            }
            case final StringValue stringValue -> {
                if (!Objects.equals(stringValue.getValue(), value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected string " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding string set: " + location.get() + " " + value);
                        return new StringSet(Set.of(stringValue.getValue(), value));
                    }
                }
            }
            case final StringSet stringSet -> {
                final Set<String> set = new HashSet<>(stringSet.getSet());
                if (set.add(value)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected string: " + location.get() + " " + value);
                    } else {
                        if (set.size() > MAX_SET_SIZE) {
                            // Convert to pattern.
                            // TODO : Create some sort of pattern expansion if possible.
                            messageReceiver.log(Severity.INFO, () ->
                                    "Converting string set to pattern: " + location.get() + " " + value);
                            return new Regex(".*");
                        } else {
                            messageReceiver.log(Severity.INFO, () ->
                                    "Expanding string set: " + location.get() + " " + value);
                            return new StringSet(set);
                        }
                    }
                }
            }
            case final Regex stringPattern -> {
                // TODO : Create some sort of pattern expansion if possible.
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    if (!pathwaysDoc.isAllowPathwayMutation()) {
                        messageReceiver.log(Severity.ERROR, () ->
                                "Unexpected type found: " + location.get() + " " + value);
                    } else {
                        messageReceiver.log(Severity.WARNING, () ->
                                "Changing to any type: " + location.get() + " " + value);
                        return new AnyTypeValue();
                    }
                }
            }
        }
        return current;
    }

}
