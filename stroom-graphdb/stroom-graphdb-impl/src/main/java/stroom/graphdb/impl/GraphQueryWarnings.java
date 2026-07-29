/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.graphdb.impl;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects the things a graph query needs to <em>tell</em> its caller without <em>failing</em> it.
 *
 * <p>Every other guardrail in Graph DB fails loudly: exceeding the path-state budget, the wall clock or the
 * in-memory row ceiling throws, and the message reaches the user as a search error. One does not - the
 * whole-graph preview's node cap silently truncates, because failing it would break the default query the Data
 * and Explore tabs open with. That leaves a third case the code had no way to express: the answer is usable, and
 * incomplete, and the user must be told which.</p>
 *
 * <p>{@link GraphSearchProvider} drains this into the result store's error consumer at
 * {@code Severity.WARNING}, which is how it reaches both graph tabs.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p><b>One instance per query.</b> It is created alongside the {@link GraphTraversalEngine} that fills it and
 * discarded with it, and it is confined to the single thread that runs the query - a graph query executes
 * synchronously on the request thread, so there is no concurrency to guard and no state to reset. Sharing one
 * between queries would report one query's truncation on another's results, so do not hold on to it.</p>
 *
 * <p>Messages are deduplicated, because a {@code UNION} of several preview branches would otherwise report the
 * same truncation once per branch - the same fact, repeated, reads as several problems.</p>
 */
@NullMarked
public final class GraphQueryWarnings {

    private final List<String> messages = new ArrayList<>();

    /**
     * Records one warning, ignoring an exact repeat.
     *
     * @param message what to tell the user; never null, never blank. It is shown verbatim, and the surface that
     *                shows it does not render severity - so it must read as an explanation on its own, naming
     *                what happened and what to do about it.
     * @throws NullPointerException     if {@code message} is null.
     * @throws IllegalArgumentException if {@code message} is blank.
     */
    public void add(final String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (!messages.contains(message)) {
            messages.add(message);
        }
    }

    /**
     * @return the warnings recorded so far, in the order they were added; never null, unmodifiable.
     */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /**
     * @return true if nothing has been recorded - the ordinary case, since most queries have nothing to report.
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphQueryWarnings" + messages;
    }
}
