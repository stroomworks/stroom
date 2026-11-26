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

package stroom.learningflow.state;

import stroom.learningflow.shared.Binding;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// An in-memory [LearnerBindings], for scenarios and for a node run without the database module. Every
/// method is synchronised, because one node's tasks share it.
public final class InMemoryLearnerBindings implements LearnerBindings {

    private final List<Held> rows = new ArrayList<>();

    @Override
    public synchronized List<Binding> forLearner(final Learner learner) {
        return rows.stream()
                .filter(row -> row.learner().equals(learner))
                .map(Held::binding)
                .toList();
    }

    @Override
    public synchronized Optional<Held> byUuid(final String bindingUuid) {
        return rows.stream()
                .filter(row -> bindingUuid.equals(row.binding().getUuid()))
                .findFirst();
    }

    @Override
    public synchronized Binding append(final Learner learner, final String docUuid, final Binding binding) {
        // One live binding per learner, as the table's unique key enforces: a caller whose view predates
        // another's promotion gets that promotion back rather than adding a second live binding.
        if (!binding.isDraft()) {
            final Optional<Binding> live = rows.stream()
                    .filter(row -> row.learner().equals(learner) && !row.binding().isDraft())
                    .map(Held::binding)
                    .findFirst();
            if (live.isPresent() && !live.get().getUuid().equals(binding.getUuid())) {
                return live.get();
            }
        }
        final Binding stored = binding.getUuid() == null
                ? binding.copy().uuid(UUID.randomUUID().toString()).build()
                : binding;
        rows.add(new Held(learner, docUuid, stored.copy().version(1).build()));
        return stored;
    }

    @Override
    public synchronized boolean replace(final Learner learner, final String docUuid, final Binding binding) {
        for (int i = 0; i < rows.size(); i++) {
            final Held row = rows.get(i);
            if (row.binding().getUuid().equals(binding.getUuid())) {
                // As in the table, a UUID identifies one binding across all learners, so another learner's
                // binding must not be overwritten.
                if (!row.learner().equals(learner)) {
                    throw new IllegalStateException("Binding " + binding.getUuid() + " is a binding of "
                                                    + row.learner() + ", not of " + learner);
                }
                // The version is checked as the DAO checks it: a binding read at one version and written
                // back after someone else has changed the row is stale, and must not overwrite that change.
                final Integer was = row.binding().getVersion();
                if (binding.getVersion() != null && was != null && !binding.getVersion().equals(was)) {
                    return false;
                }
                rows.set(i, new Held(learner, docUuid, binding.copy()
                        .version(was == null
                                ? 1
                                : was + 1)
                        .build()));
                return true;
            }
        }
        rows.add(new Held(learner, docUuid, binding.copy().version(1).build()));
        return true;
    }

    @Override
    public synchronized void remove(final Learner learner, final String bindingUuid) {
        rows.removeIf(row -> row.learner().equals(learner) && bindingUuid.equals(row.binding().getUuid()));
    }

    /// Updates the learner's bindings to the document it runs under (see [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        rows.replaceAll(row -> row.learner().equals(learner)
                ? new Held(learner, docUuid, row.binding())
                : row);
    }

    /// Returns every row of every learner, oldest first, for the in-memory Supervisor list.
    ///
    /// @return The rows.
    public synchronized List<Held> all() {
        return List.copyOf(rows);
    }
}
