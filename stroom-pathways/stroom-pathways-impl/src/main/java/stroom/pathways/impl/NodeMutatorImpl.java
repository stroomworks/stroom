/*
 * Copyright 2016-2025 Crown Copyright
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

import stroom.pathways.impl.events.ConstraintDiscoveryEvent;
import stroom.pathways.impl.events.ConstraintMutationEvent;
import stroom.pathways.impl.events.NodeDiscoveryEvent;
import stroom.pathways.impl.events.PathwayEventType;
import stroom.pathways.impl.events.PathwayRootDiscoveryEvent;
import stroom.pathways.impl.events.RequiredConstraintAbsentEvent;
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
import stroom.pathways.shared.pathway.PathNodeSequence;
import stroom.pathways.shared.pathway.Regex;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class NodeMutatorImpl {

    private static final int MAX_SET_SIZE = 10;

    private final Comparator<Span> spanComparator;
    private final PathKeyFactory pathKeyFactory;

    public NodeMutatorImpl(final Comparator<Span> spanComparator,
                           final PathKeyFactory pathKeyFactory) {
        this.spanComparator = spanComparator;
        this.pathKeyFactory = pathKeyFactory;
    }


    public PathNode process(final Trace trace,
                            final PathKey pathKey,
                            final PathNode pathNode,
                            final MessageReceiver messageReceiver,
                            final PathwaysDoc pathwaysDoc) {
        final Span root = trace.root();

        final PathNode node;
        if (pathNode == null) {
//            messageReceiver.log(Severity.INFO, () -> "Adding new root path: " + root.getName());
            node = new PathNode(root.getName());
            if (pathwaysDoc.isAllowPathwayCreation()) {
                messageReceiver.event(pathwaysDoc, root.getName(),
                        new PathwayRootDiscoveryEvent(node.getUuid(), node.getName(), PathwayEventType.MUTATION));
            } else {
                messageReceiver.event(pathwaysDoc, root.getName(),
                        new PathwayRootDiscoveryEvent(node.getUuid(), node.getName(), PathwayEventType.VIOLATION));
            }
        } else {
            node = pathNode;
        }

        final Map<PathKey, Map<String, Map<PathKey, PathNodeSequence>>> maps = new HashMap<>();
        final Map<String, Map<PathKey, PathNodeSequence>> map = maps.computeIfAbsent(pathKey, k -> new HashMap<>());
        return walk(trace, root, node, map, messageReceiver, pathwaysDoc);


//        final Map<PathKey, Map<String, Map<PathKey, PathNodeSequence>>> maps = new HashMap<>();
//        final Span root = trace.root();
//        final PathKey pathKey = pathKeyFactory.create(Collections.singletonList(root));
//
//        PathNode node = roots.get(pathKey);
    }

    private PathNode walk(final Trace trace,
                          final Span parentSpan,
                          final PathNode parentNode,
                          final Map<String, Map<PathKey, PathNodeSequence>> map,
                          final MessageReceiver messageReceiver,
                          final PathwaysDoc pathwaysDoc) {
        final PathNode.Builder pathNodeBuilder = addConstraints(parentNode, parentSpan, trace.root().getName(), messageReceiver, pathwaysDoc);

        final List<Span> childSpans = trace.children(parentSpan);
        final List<Span> sortedSpans = new ArrayList<>(childSpans);
        sortedSpans.sort(spanComparator);
        final PathKey pathKey = pathKeyFactory.create(sortedSpans);

        // Load inner map.
        final Map<PathKey, PathNodeSequence> innerMap = map.computeIfAbsent(parentNode.getUuid(), k -> {
            final Map<PathKey, PathNodeSequence> subMap = new HashMap<>();
            parentNode.getTargets().forEach(target -> subMap.put(target.getPathKey(), target));
            return subMap;
        });

        // Get current path node list.
        final PathNodeSequence pathNodeList = innerMap.get(pathKey);
        if (pathNodeList == null && !pathwaysDoc.isAllowPathwayMutation()) {
            messageReceiver.event(pathwaysDoc, trace.root().getName(),
                    new NodeDiscoveryEvent(parentNode.getUuid(), null, pathKey.toString(), PathwayEventType.VIOLATION));

        } else {
            // Loop over all child spans.
            final List<PathNode> childNodes = new ArrayList<>(sortedSpans.size());
            for (int i = 0; i < sortedSpans.size(); i++) {
                final Span span = sortedSpans.get(i);

                final PathNode pathNode;
                if (pathNodeList != null) {
                    pathNode = pathNodeList.getNodes().get(i);
                } else {
                    final List<String> path = new ArrayList<>(parentNode.getPath());
                    path.add(span.getName());
//                    messageReceiver.log(Severity.INFO, () -> "Adding new path: " + path);
                    pathNode = new PathNode(span.getName(), path);
                    messageReceiver.event(pathwaysDoc, trace.root().getName(),
                            new NodeDiscoveryEvent(parentNode.getUuid(), pathNode.getUuid(), pathNode.getName(), PathwayEventType.MUTATION));
                }

                // Follow the path deeper.
                final PathNode updated = walk(trace, span, pathNode, map, messageReceiver, pathwaysDoc);
                childNodes.add(updated);
            }

            // Update the path node list.
            innerMap.put(pathKey, new PathNodeSequence(UUID.randomUUID().toString(), pathKey, childNodes));

            // Update the targets for this node.
            pathNodeBuilder.targets(new ArrayList<>(innerMap.values()));
        }

        return pathNodeBuilder.build();
    }

    private PathNode.Builder addConstraints(final PathNode pathNode,
                                            final Span span,
                                            final String rootName,
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

        setOrExpand(constraints, pathNode, "duration", duration, false, rootName, messageReceiver, pathwaysDoc);

        // Set or expand flags.
        setOrExpand(constraints, pathNode, "flags", span.getFlags(), false, rootName, messageReceiver, pathwaysDoc);

        // Set or expand kind.
        setOrExpand(constraints, pathNode, "kind", span.getKind().name(), false, rootName, messageReceiver, pathwaysDoc);

        // Create attribute sets.
        final Map<String, KeyValue> attributes = span
                .getAttributes()
                .stream()
                .collect(Collectors.toMap(kv -> "attribute." + kv.getKey(), Function.identity()));

        // Make required constraints optional if they don't exist in this set.
        final Map<String, Constraint> newConstraints = new HashMap<>(constraints.size());
        constraints.forEach((key, value) -> {
            if (!attributes.containsKey(key) && !value.isOptional() && key.startsWith("attribute.")) {
                if (!pathwaysDoc.isAllowConstraintMutation()) {
//                    messageReceiver.log(Severity.ERROR, () ->
//                            "Attribute required: " + pathNode.getPath() + " " + key);
                    messageReceiver.event(pathwaysDoc, rootName, new RequiredConstraintAbsentEvent(
                            pathNode.getUuid(),
                            pathNode.getName(),
                            key,
                            PathwayEventType.VIOLATION
                    ));
                } else {
//                    messageReceiver.log(Severity.INFO, () -> "Making constraint optional: " +
//                                                             pathNode.getPath() + " " +
//                                                             key);
                    newConstraints.put(key, new Constraint(value.getName(), value.getValue(), true));
                    messageReceiver.event(pathwaysDoc, rootName, new ConstraintMutationEvent(
                            pathNode.getUuid(),
                            pathNode.getName(),
                            value,
                            newConstraints.get(key),
                            PathwayEventType.VIOLATION
                    ));
                }
            } else {
                newConstraints.put(key, value);
            }
        });

        // Set or expand attributes.
        attributes.forEach((key, value) ->
                setOrExpand(newConstraints, pathNode, key, value.getValue(), optional, rootName, messageReceiver, pathwaysDoc));

        pathNodeBuilder.constraints(newConstraints);
        return pathNodeBuilder;
    }

    private void setOrExpand(final Map<String, Constraint> constraints,
                             final PathNode pathNode,
                             final String name,
                             final Object value,
                             final boolean optional,
                             final String rootName,
                             final MessageReceiver messageReceiver,
                             final PathwaysDoc pathwaysDoc) {
        final Supplier<String> location = () -> pathNode.getPath() + " " + name;
        final Constraint constraint = constraints.get(name);

        if (value == null) {
            if (!optional) {
//                messageReceiver.log(Severity.ERROR, () ->
//                        "Null value for: " + location.get());
                messageReceiver.event(pathwaysDoc, rootName, new RequiredConstraintAbsentEvent(
                        pathNode.getUuid(),
                        pathNode.getName(),
                        name,
                        PathwayEventType.VIOLATION
                ));
            }
        } else {
            final boolean opt = NullSafe.getOrElse(constraint, Constraint::isOptional, optional);
            final ConstraintValue newConstraintValue;
            switch (value) {
                case final Integer val ->
                        newConstraintValue = calcIntConstraintChange(getConstraintValue(constraint), val);
                case final Long val ->
                        newConstraintValue = calcLongConstraintChange(getConstraintValue(constraint), val);
                case final Boolean val ->
                        newConstraintValue = calcBooleanConstraintChange(getConstraintValue(constraint), val);
                case final String val ->
                        newConstraintValue = calcStringConstraintChange(getConstraintValue(constraint), val);
                case final NanoTime val ->
                        newConstraintValue = calcNanoTimeConstraintChange(getConstraintValue(constraint), val);
                case final AnyValue val -> {
                    // Unwrap.
                    if (val.getStringValue() != null) {
                        setOrExpand(constraints,
                                pathNode,
                                name,
                                val.getStringValue(),
                                optional,
                                rootName,
                                messageReceiver,
                                pathwaysDoc);
                    } else if (val.getBoolValue() != null) {
                        setOrExpand(constraints,
                                pathNode,
                                name,
                                val.getBoolValue(),
                                optional,
                                rootName,
                                messageReceiver,
                                pathwaysDoc);
                    } else if (val.getIntValue() != null) {
                        setOrExpand(constraints,
                                pathNode,
                                name,
                                val.getIntValue(),
                                optional,
                                rootName,
                                messageReceiver,
                                pathwaysDoc);
                    }
                    //Changes handled by the unwrap sub-call
                    newConstraintValue = null;
                }
                // TODO : Add constraints for other attribute types.
                default -> {
                    newConstraintValue = null;
                }
            }

            if (newConstraintValue == null) {
                //No change
                return;
            }

            final Constraint newConstraint = new Constraint(name, newConstraintValue, opt);
            //New constraint discovered
            if (constraint == null) {
                final boolean creationAllowed = pathwaysDoc.isAllowConstraintCreation();
                if (creationAllowed) {
                    constraints.put(name, newConstraint);
                }
                messageReceiver.event(pathwaysDoc, rootName, new ConstraintDiscoveryEvent(
                        pathNode.getUuid(),
                        pathNode.getName(),
                        newConstraint,
                        creationAllowed ? PathwayEventType.MUTATION : PathwayEventType.VIOLATION
                ));
            //Constraint mutation
            } else {
                final boolean mutationAllowed = pathwaysDoc.isAllowConstraintMutation();
                if(mutationAllowed) {
                    constraints.put(name, newConstraint);
                }
                messageReceiver.event(pathwaysDoc, rootName, new ConstraintMutationEvent(
                        pathNode.getUuid(),
                        pathNode.getName(),
                        constraint,
                        newConstraint,
                        mutationAllowed ? PathwayEventType.MUTATION : PathwayEventType.VIOLATION
                ));
            }
        }
    }

    private ConstraintValue getConstraintValue(final Constraint constraint) {
        if (constraint == null) {
            return null;
        }
        return constraint.getValue();
    }

    private ConstraintValue calcNanoTimeConstraintChange(final ConstraintValue current,
                                                         final NanoTime value) {
        switch (current) {
            case null -> {
                return new NanoTimeValue(value);
            }
            case final NanoTimeValue nanoTimeValue -> {
                if (!Objects.equals(nanoTimeValue.getValue(), value)) {
                    if (nanoTimeValue.getValue().isGreaterThan(value)) {
                        return new NanoTimeRange(value, nanoTimeValue.getValue());
                    } else if (nanoTimeValue.getValue().isLessThan(value)) {
                        return new NanoTimeRange(nanoTimeValue.getValue(), value);
                    }
                }
            }
            case final NanoTimeRange timeRange -> {
                if (timeRange.getMin().isGreaterThan(value)) {
                    return new NanoTimeRange(value, timeRange.getMax());
                } else if (timeRange.getMax().isLessThan(value)) {
                    return new NanoTimeRange(timeRange.getMin(), value);
                }
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    return new AnyTypeValue();
                }
            }
        }
        return null;
    }

    private ConstraintValue calcIntConstraintChange(final ConstraintValue current,
                                                    final int value) {
        switch (current) {
            case null -> {
                return new IntegerValue(value);
            }
            case final IntegerValue intValue -> {
                if (!Objects.equals(intValue.getValue(), value)) {
                    return new IntegerSet(Set.of(intValue.getValue(), value));
                }
            }
            case final IntegerSet intSet -> {
                final Set<Integer> set = new HashSet<>(intSet.getSet());
                if (set.add(value)) {
                    if (set.size() > MAX_SET_SIZE) {
                        // Convert to range.
                        int min = value;
                        int max = value;
                        for (final int num : intSet.getSet()) {
                            min = Math.min(min, num);
                            max = Math.max(max, num);
                        }
                        return new IntegerRange(min, max);
                    } else {
                        return new IntegerSet(set);
                    }
                }
            }
            case final IntegerRange intRange -> {
                if (intRange.getMin() > value) {
                    return new IntegerRange(value, intRange.getMax());
                } else if (intRange.getMax() < value) {
                    return new IntegerRange(intRange.getMin(), value);
                }
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    return new AnyTypeValue();
                }
            }
        }
        return null;
    }

    private ConstraintValue calcLongConstraintChange(final ConstraintValue current,
                                                     final long value) {
        switch (current) {
            case null -> {
                return new LongValue(value);
            }
            case final LongValue longValue -> {
                if (!Objects.equals(longValue.getValue(), value)) {
                    return new LongSet(Set.of(longValue.getValue(), value));
                }
            }
            case final LongSet longSet -> {
                final Set<Long> set = new HashSet<>(longSet.getSet());
                if (set.add(value)) {
                    if (set.size() > MAX_SET_SIZE) {
                        // Convert to range.
                        long min = value;
                        long max = value;
                        for (final long num : longSet.getSet()) {
                            min = Math.min(min, num);
                            max = Math.max(max, num);
                        }
                        return new LongRange(min, max);
                    } else {
                        return new LongSet(set);
                    }
                }
            }
            case final LongRange longRange -> {
                if (longRange.getMin() > value) {
                    return new LongRange(value, longRange.getMax());
                } else if (longRange.getMax() < value) {
                    return new LongRange(longRange.getMin(), value);
                }
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    return new AnyTypeValue();
                }
            }
        }
        return null;
    }

    private ConstraintValue calcBooleanConstraintChange(final ConstraintValue current,
                                                        final boolean value) {
        switch (current) {
            case null -> {
                return new BooleanValue(value);
            }
            case final BooleanValue booleanValue -> {
                if (!Objects.equals(booleanValue.getValue(), value)) {
                    return new AnyBoolean();
                }
            }
            case final AnyBoolean booleanValue -> {
                // Do nothing.
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    return new AnyTypeValue();
                }
            }
        }
        return null;
    }

    private ConstraintValue calcStringConstraintChange(final ConstraintValue current,
                                                       final String value) {
        switch (current) {
            case null -> {
                return new StringValue(value);
            }
            case final StringValue stringValue -> {
                if (!Objects.equals(stringValue.getValue(), value)) {
                    return new StringSet(Set.of(stringValue.getValue(), value));
                }
            }
            case final StringSet stringSet -> {
                final Set<String> set = new HashSet<>(stringSet.getSet());
                if (set.add(value)) {
                        if (set.size() > MAX_SET_SIZE) {
                            // Convert to pattern.
                            // TODO : Create some sort of pattern expansion if possible.
                            return new Regex(".*");
                        } else {
                            return new StringSet(set);
                        }
                }
            }
            case final Regex stringPattern -> {
                // TODO : Create some sort of pattern expansion if possible.
            }
            default -> {
                if (!(current instanceof AnyTypeValue)) {
                    return new AnyTypeValue();
                }
            }
        }
        return null;
    }
}
