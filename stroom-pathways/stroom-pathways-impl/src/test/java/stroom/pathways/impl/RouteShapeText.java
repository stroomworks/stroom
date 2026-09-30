/*
 * Copyright 2026 Crown Copyright
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

import stroom.pathways.shared.pathway.PathNode;

import java.util.HashMap;
import java.util.Map;

/**
 * A trace's shape written out, so a test can say what a trace did in one line.
 *
 * <p>{@code name} is a node that ran nothing, {@code name[a b]} one that ran a and then b,
 * {@code (a b)~} work that ran over and over and stopped part way through the last time, and
 * {@code {a | b}} runs that happened at the same time. Work that repeated and did finish is simply
 * said once, so it has no mark of its own.
 */
final class RouteShapeText {

    private RouteShapeText() {
    }

    static String of(final RouteShape shape, final PathNode root) {
        final Map<String, String> names = new HashMap<>();
        collect(root, names);
        return render(shape, names);
    }

    /**
     * What one node ran, without the node itself — for asserting on a node partway down the model
     * where naming everything above it would say the same thing every time.
     */
    static String under(final RouteShape shape, final PathNode root, final String nodeName) {
        final Map<String, String> names = new HashMap<>();
        collect(root, names);
        final RouteShape found = find(shape, names, nodeName);
        if (found == null) {
            return "";
        }
        final String text = render(found, names);
        final int open = text.indexOf('[');
        return open < 0
                ? ""
                : text.substring(open + 1, text.length() - 1);
    }

    private static RouteShape find(final RouteShape shape,
                                   final Map<String, String> names,
                                   final String nodeName) {
        if (shape.kind() == RouteShape.Kind.NODE && nodeName.equals(names.get(shape.nodeUuid()))) {
            return shape;
        }
        for (final RouteShape step : shape.steps()) {
            final RouteShape found = find(step, names, nodeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void collect(final PathNode node, final Map<String, String> names) {
        names.put(node.getUuid(), node.getName());
        node.getChildren().forEach(child -> collect(child, names));
    }

    private static String render(final RouteShape shape, final Map<String, String> names) {
        final String separator = shape.kind() == RouteShape.Kind.CONCURRENT
                ? " | "
                : " ";
        final StringBuilder sb = new StringBuilder();
        for (final RouteShape step : shape.steps()) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(render(step, names));
        }
        return switch (shape.kind()) {
            case UNFINISHED -> "(" + sb + ")~";
            case CONCURRENT -> "{" + sb + "}";
            case RUN -> sb.toString();
            case NODE -> shape.steps().isEmpty()
                    ? names.get(shape.nodeUuid())
                    : names.get(shape.nodeUuid()) + "[" + sb + "]";
        };
    }
}
