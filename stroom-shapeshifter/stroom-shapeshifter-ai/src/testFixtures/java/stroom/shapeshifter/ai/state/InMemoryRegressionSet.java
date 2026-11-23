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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.RegressionSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The corpus as a map of lists: what scenarios run over — node-local and gone on restart. Every method
/// is synchronised: one node's tasks share it.
public final class InMemoryRegressionSet implements RegressionSet {

    private final Map<String, List<Accepted>> accepted = new HashMap<>();
    /// The feed each binding's newest entries came from.
    private final Map<String, String> feeds = new HashMap<>();

    @Override
    public synchronized List<Accepted> accepted(final String bindingUuid) {
        return List.copyOf(accepted.getOrDefault(bindingUuid, List.of()));
    }

    @Override
    public synchronized void accept(final String docUuid,
                                    final String bindingUuid,
                                    final String feed,
                                    final String type,
                                    final List<Accepted> entries,
                                    final int cap) {
        // As a node's store has it: a copy kept on no feed is not kept at all.
        Objects.requireNonNull(feed, "The feed to keep binding " + bindingUuid + "'s records on");
        append(bindingUuid, entries, cap);
        feeds.put(bindingUuid, feed);
    }

    @Override
    public synchronized Optional<String> feedOf(final String bindingUuid) {
        return accepted.containsKey(bindingUuid)
                ? Optional.ofNullable(feeds.get(bindingUuid))
                : Optional.empty();
    }

    @Override
    public synchronized void discard(final String bindingUuid) {
        accepted.remove(bindingUuid);
        feeds.remove(bindingUuid);
    }

    @Override
    public synchronized void move(final String fromBindingUuid, final String toBindingUuid, final int cap) {
        final List<Accepted> moving = accepted.remove(fromBindingUuid);
        final String movingFeed = feeds.remove(fromBindingUuid);
        if (moving != null) {
            append(toBindingUuid, moving, cap);
            // Appended after what the binding held, so they are its newest.
            if (movingFeed != null) {
                feeds.put(toBindingUuid, movingFeed);
            }
        }
    }

    private void append(final String bindingUuid, final List<Accepted> entries, final int cap) {
        final List<Accepted> kept = accepted.computeIfAbsent(bindingUuid, k -> new ArrayList<>());
        kept.addAll(entries);
        // The cap keeps the most recent; the oldest accepted records are the first to go.
        while (kept.size() > cap) {
            kept.remove(0);
        }
    }
}
