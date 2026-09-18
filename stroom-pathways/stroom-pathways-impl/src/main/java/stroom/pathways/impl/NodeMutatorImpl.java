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
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.NanoTimeValue;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Regex;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final String CHILD_ORDER = "childOrder";
    private static final String OCCURRENCES = "occurrences";

    private final CanonicalSpanOrder spanOrder;

    public NodeMutatorImpl(final CanonicalSpanOrder spanOrder) {
        this.spanOrder = spanOrder;
    }


    public PathNode process(final Trace trace,
                            final PathKey pathKey,
                            final PathNode pathNode,
                            final MessageReceiver messageReceiver,
                            final PathwaysDoc pathwaysDoc) {
        final Span root = trace.root();
        if (pathNode == null && !pathwaysDoc.isAllowPathwayCreation()) {
            messageReceiver.log(Severity.ERROR, () -> "Invalid path: " + pathKey);
            return pathNode;
        }


        final PathNode node;
        if (pathNode == null) {
            messageReceiver.log(Severity.INFO, () -> "Adding new root path: " + root.getName());
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
        // This trace's children grouped by name, first appearance first. The same name twice is one
        // child that happened twice, not two children.
        final Map<String, List<Span>> spansByName = new LinkedHashMap<>();
        spanOrder.sort(trace.children(parentSpan)).forEach(span -> spansByName
                .computeIfAbsent(span.getName(), k -> new ArrayList<>())
                .add(span));

        // The child names in the order they were first reached, kept on the parent as a constraint.
        // A route that starts doing the same work in a different order widens that constraint rather
        // than becoming a route of its own. How many times each one ran is counted on the child, so
        // repeats are left out here.
        final boolean hasOrder = parentNode.getConstraints() != null &&
                                 parentNode.getConstraints().containsKey(CHILD_ORDER);
        final String childOrder = spansByName.isEmpty() && !hasOrder
                ? null
                : String.join(" > ", spansByName.keySet());

        final PathNode.Builder pathNodeBuilder =
                addConstraints(parentNode, parentSpan, childOrder, messageReceiver, pathwaysDoc);

        final Map<String, PathNode> existing = new HashMap<>();
        NullSafe.list(parentNode.getChildren()).forEach(child -> existing.put(child.getName(), child));

        // Every name the model knows plus every name this trace carried, in a fixed order so the
        // stored children do not shuffle between writes.
        final Set<String> names = new TreeSet<>(existing.keySet());
        names.addAll(spansByName.keySet());

        final List<PathNode> children = new ArrayList<>(names.size());
        for (final String name : names) {
            final List<Span> spans = spansByName.get(name);
            PathNode child = existing.get(name);

            if (child == null) {
                if (!pathwaysDoc.isAllowPathwayMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Invalid path: " + parentNode.getPath() + " " + name);
                    continue;
                }
                final List<String> path = new ArrayList<>(parentNode.getPath());
                path.add(name);
                messageReceiver.log(Severity.INFO, () -> "Adding new path: " + path);
                child = new PathNode(name, path);
            }

            // Fold every span of this name into the one child, then record how many there were. A
            // child the model knows about that this trace did not carry happened no times.
            if (spans != null) {
                for (final Span span : spans) {
                    child = walk(trace, span, child, messageReceiver, pathwaysDoc);
                }
            }
            children.add(withCount(child,
                    spans == null
                            ? 0
                            : spans.size(),
                    messageReceiver,
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
                .collect(Collectors.toMap(kv -> "attribute." + kv.getKey(), Function.identity(),
                        (first, second) -> second));

        // Make required constraints optional if they don't exist in this set.
        final Map<String, Constraint> newConstraints = new HashMap<>(constraints.size());
        constraints.forEach((key, value) -> {
            if (!attributes.containsKey(key) && !value.isOptional() && key.startsWith("attribute.")) {
                if (!pathwaysDoc.isAllowConstraintMutation()) {
                    messageReceiver.log(Severity.ERROR, () ->
                            "Attribute required: " + pathNode.getPath() + " " + key);
                } else {
                    messageReceiver.log(Severity.INFO, () -> "Making constraint optional: " +
                                                             pathNode.getPath() + " " +
                                                             key);
                    newConstraints.put(key, new Constraint(value.getName(), value.getValue(), true));
                }
            } else {
                newConstraints.put(key, value);
            }
        });

        // Set or expand attributes.
        attributes.forEach((key, value) ->
                setOrExpand(newConstraints, pathNode, key, value.getValue(), optional, messageReceiver, pathwaysDoc));

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
                    case final Integer val -> constraints.put(name, new Constraint(name,
                            createIntConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt));
                    case final Long val -> constraints.put(name, new Constraint(name,
                            createLongConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt));
                    case final Boolean val -> constraints.put(name, new Constraint(name,
                            createBooleanConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt));
                    case final String val -> constraints.put(name, new Constraint(name,
                            createStringConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt));
                    case final NanoTime val -> constraints.put(name, new Constraint(name,
                            createNanoTimeConstraint(location,
                                    getConstraintValue(constraint),
                                    val,
                                    messageReceiver,
                                    pathwaysDoc),
                            opt));
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
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding max time constraint: " + location.get() + " " + value);
                        return new NanoTimeRange(value, nanoTimeValue.getValue());
                    } else if (nanoTimeValue.getValue().isLessThan(value)) {
                        messageReceiver.log(Severity.INFO, () ->
                                "Expanding min time constraint: " + location.get() + " " + value);
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
