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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.shared.Binding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// The bindings of A41 in memory, until the A26 module holds them. Every method is synchronised: one node's
/// tasks share it.
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
        final Binding stored = binding.getUuid() == null
                ? binding.copy().uuid(UUID.randomUUID().toString()).build()
                : binding;
        rows.add(new Held(learner, docUuid, stored));
        return stored;
    }

    @Override
    public synchronized void replace(final Learner learner, final String docUuid, final Binding binding) {
        for (int i = 0; i < rows.size(); i++) {
            final Held row = rows.get(i);
            if (row.binding().getUuid().equals(binding.getUuid())) {
                // As the table has it: a uuid names one binding across every learner, so another
                // learner's is not this one's to overwrite.
                if (!row.learner().equals(learner)) {
                    throw new IllegalStateException("Binding " + binding.getUuid() + " is a binding of "
                                                    + row.learner() + ", not of " + learner);
                }
                rows.set(i, new Held(learner, docUuid, binding));
                return;
            }
        }
        rows.add(new Held(learner, docUuid, binding));
    }

    @Override
    public synchronized void remove(final Learner learner, final String bindingUuid) {
        rows.removeIf(row -> row.learner().equals(learner) && bindingUuid.equals(row.binding().getUuid()));
    }

    /// The learner's bindings brought to the document it runs under (see [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        rows.replaceAll(row -> row.learner().equals(learner)
                ? new Held(learner, docUuid, row.binding())
                : row);
    }

    /// Every row of every learner, oldest first: what the Supervisor's list reads in memory.
    ///
    /// @return The rows.
    public synchronized List<Held> all() {
        return List.copyOf(rows);
    }
}
