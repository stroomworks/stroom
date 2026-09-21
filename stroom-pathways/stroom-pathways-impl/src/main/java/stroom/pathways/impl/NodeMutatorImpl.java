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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class NodeMutatorImpl {

    private static final int MAX_SET_SIZE = 10;
    private static final String ATTRIBUTE_PREFIX = "attribute.";
    private static final String CHILD_ORDER = "childOrder";
    private static final String OCCURRENCES = "occurrences";

    private final CanonicalSpanOrder spanOrder;
    private final IgnoredAttributes ignoredAttributes;

    // What this trace taught the model: a node it had not seen, or a constraint it had to add or
    // widen. One of these is made per trace, so the list covers that trace and no other. Kept in the
    // order the changes happened so a replay can follow them.
    private final List<PathwayMutation> mutations = new ArrayList<>();

    // Where the change being recorded is happening. Set as the walk descends, because the methods that
    // notice a constraint moving are several calls below the one that knows which span it came from.
    private NanoTime time;
    private String traceId;
    private String spanId;
    private List<String> path;

    public NodeMutatorImpl(final CanonicalSpanOrder spanOrder,
                           final IgnoredAttributes ignoredAttributes) {
        this.spanOrder = spanOrder;
        this.ignoredAttributes = ignoredAttributes;
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
     * Whether the trace taught the model anything. The same question as whether it made any changes.
     */
    public boolean isChanged() {
        return !mutations.isEmpty();
    }

    private void record(final MutationType type,
                        final String constraint,
                        final ConstraintValue oldValue,
                        final ConstraintValue newValue) {
        mutations.add(new PathwayMutation(time, traceId, spanId, path, constraint, type, oldValue,
                newValue));
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
        path = List.of();
        final MessageReceiver messages =
                MessageReceiver.forSpan(messageReceiver, traceId, spanId);
        if (pathNode == null && !pathwaysDoc.isAllowPathwayCreation()) {
            messages.log(Severity.ERROR, () -> "Invalid path: " + pathKey);
            return pathNode;
        }

        final PathNode node;
        if (pathNode == null) {
            messages.log(Severity.INFO, () -> "Adding new root path: " + root.getName());
            record(MutationType.PATHWAY_ADDED, null, null, null);
            node = new PathNode(root.getName());
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
        path = parentNode.getPath();
        final MessageReceiver messages =
                MessageReceiver.forSpan(messageReceiver, traceId, spanId);

        // This trace's children grouped by name, first appearance first. The same name twice is one
        // child that happened twice, not two children.
        final Map<String, List<Span>> spansByName = new LinkedHashMap<>();
        spanOrder.sort(trace.children(parentSpan)).forEach(span -> spansByName
                .computeIfAbsent(span.getName(), k -> new ArrayList<>())
                .add(span));

        // The child names in the order they were first reached, kept on the parent as a constraint.
        // A route that starts doing the same work in a different order widens that constraint rather
        // than becoming a route of its own. How many times each one ran is counted on the child, so
        // repeats are left out here, and so is a trace that reached no children at all — that is
        // already recorded as a count of zero on each child the model knows.
        final String childOrder = spansByName.isEmpty()
                ? null
                : String.join(" > ", spansByName.keySet());

        final PathNode.Builder pathNodeBuilder =
                addConstraints(parentNode, parentSpan, childOrder, messages, pathwaysDoc);

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
                final List<String> parentPath = this.path;
                this.path = path;
                record(MutationType.NODE_ADDED, null, null, null);
                this.path = parentPath;
                child = new PathNode(name, path);
            }

            // Fold every span of this name into the one child, then record how many there were. A
            // child the model knows about that this trace did not carry happened no times.
            if (spans != null) {
                for (final Span span : spans) {
                    child = walk(trace, span, child, messageReceiver, pathwaysDoc);
                }
                spanId = parentSpan.getSpanId();
                path = parentNode.getPath();
            }
            children.add(withCount(child,
                    spans == null
                            ? 0
                            : spans.size(),
                    messages,
                    pathwaysDoc));
        }

        pathNodeBuilder.children(children);
        return pathNodeBuilder.build();
    }

    private PathNode withCount(final PathNode pathNode,
                               final int count,
                               final MessageReceiver messageReceiver,
                               final PathwaysDoc pathwaysDoc) {
        final Map<String, Constraint> constraints = pathNode.getConstraints() == null
                ? new HashMap<>()
                : new HashMap<>(pathNode.getConstraints());
        setOrExpand(constraints, pathNode, OCCURRENCES, count, false, messageReceiver, pathwaysDoc);
        return pathNode.copy().constraints(constraints).build();
    }

    private PathNode.Builder addConstraints(final PathNode pathNode,
                                            final Span span,
                                            final String childOrder,
                                            final MessageReceiver messageReceiver,
                                            final PathwaysDoc pathwaysDoc) {
        final PathNode.Builder pathNodeBuilder = pathNode.copy();

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

        // Set or expand the order the children ran in. Null for a node that has never had any.
        if (childOrder != null) {
            setOrExpand(constraints, pathNode, CHILD_ORDER, childOrder, false, messageReceiver, pathwaysDoc);
        }

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
                    record(MutationType.CONSTRAINT_OPTIONAL, key, value.getValue(), value.getValue());
                    newConstraints.put(key, new Constraint(value.getName(), value.getValue(), true));
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
                if (!(NullSafe.get(newConstraints.get(key), Constraint::getValue)
                      instanceof AnyTypeValue)) {
                    // Not through put(): AnyTypeValue defines no equals, so every trace would look
                    // like a change. Recorded once, and thereafter this branch does nothing.
                    final Constraint was = newConstraints.get(key);
                    record(MutationType.CONSTRAINT_IGNORED, key,
                            NullSafe.get(was, Constraint::getValue), new AnyTypeValue());
                    newConstraints.put(key, new Constraint(key, new AnyTypeValue(), optional));
                }
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
                    case final Integer val -> put(constraints, name,
                            createIntConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final Long val -> put(constraints, name,
                            createLongConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final Boolean val -> put(constraints, name,
                            createBooleanConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final String val -> put(constraints, name,
                            createStringConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt);
                    case final NanoTime val -> put(constraints, name,
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
                     final String name,
                     final ConstraintValue value,
                     final boolean optional) {
        final Constraint existing = constraints.get(name);
        if (existing == null) {
            record(MutationType.CONSTRAINT_ADDED, name, null, value);
        } else if (existing.isOptional() != optional || !Objects.equals(existing.getValue(), value)) {
            record(widening(existing.getValue(), value), name, existing.getValue(), value);
        }
        constraints.put(name, new Constraint(name, value, optional));
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
