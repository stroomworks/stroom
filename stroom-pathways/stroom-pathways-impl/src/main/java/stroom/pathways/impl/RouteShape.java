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

import java.util.List;

/**
 * What one trace did at one point, before it is written down: a node and the children it ran there in
 * the order it ran them, a run of work that did not finish, or runs that happened at the same time.
 *
 * <p>The stored form is {@link stroom.pathways.shared.pathway.RouteStep}, which says the same thing in
 * positions rather than names. This one is built while the trace is walked, when the names are what is
 * to hand and no numbering has been handed out yet.
 *
 * <p>Two shapes are equal where the work was the same, which is what lets a repeat be found and what
 * lets the writer keep one copy of a subtree that many routes reach.
 *
 * @param nodeUuid the node reached, or null for anything but {@link Kind#NODE}.
 * @param steps    what ran here: the children in order, the one turn of an unfinished run, the runs
 *                 of a concurrent one, or what one of those runs did.
 * @param kind     what this shape is.
 */
record RouteShape(String nodeUuid, List<RouteShape> steps, Kind kind) {

    enum Kind {
        /** A node the trace reached. */
        NODE,
        /** Work that ran over and over and stopped part way through the last time. */
        UNFINISHED,
        /** Runs that happened at the same time as each other, in no particular order. */
        CONCURRENT,
        /** One run inside a concurrent shape, in the order that thread did it. */
        RUN
    }

    static RouteShape of(final String nodeUuid, final List<RouteShape> steps) {
        return new RouteShape(nodeUuid, List.copyOf(steps), Kind.NODE);
    }

    static RouteShape unfinished(final List<RouteShape> unit) {
        return new RouteShape(null, List.copyOf(unit), Kind.UNFINISHED);
    }

    static RouteShape concurrent(final List<RouteShape> runs) {
        return new RouteShape(null, List.copyOf(runs), Kind.CONCURRENT);
    }

    static RouteShape run(final List<RouteShape> steps) {
        return new RouteShape(null, List.copyOf(steps), Kind.RUN);
    }

    boolean isUnfinished() {
        return kind == Kind.UNFINISHED;
    }
}
