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

package stroom.learningflow.state;

import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Learner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/// An in-memory [Guidance], holding the messages people leave for learners as a list, for scenarios and
/// for a node run without the database module. It is local to one node and lost on restart. Every method
/// is synchronised, because one node's tasks share it.
public final class InMemoryGuidance implements Guidance {

    private final List<Row> rows = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1L);

    @Override
    public synchronized long given(final Learner learner,
                                   final String docUuid,
                                   final String message,
                                   final String author) {
        final long id = nextId.getAndIncrement();
        rows.add(new Row(id, learner, docUuid, new Given(id, message, author, System.currentTimeMillis())));
        return id;
    }

    @Override
    public synchronized List<Given> standing(final Learner learner) {
        return rows.stream()
                .filter(row -> row.learner().equals(learner))
                .map(Row::given)
                .toList();
    }

    @Override
    public synchronized void withdraw(final String docUuid, final long id) {
        rows.removeIf(row -> row.docUuid().equals(docUuid) && row.given().id() == id);
    }

    /// Updates the learner's guidance to the document it runs under (see [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        rows.replaceAll(row -> row.learner().equals(learner)
                ? new Row(row.id(), learner, docUuid, row.given())
                : row);
    }


    // --------------------------------------------------------------------------------


    private record Row(long id, Learner learner, String docUuid, Given given) {

    }
}
