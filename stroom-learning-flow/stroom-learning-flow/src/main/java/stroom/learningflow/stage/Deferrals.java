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

package stroom.learningflow.stage;

import java.util.List;
import java.util.Optional;

/// Storage for the streams that are waiting, and what they are waiting on: one row per stream a learner has
/// deferred, recording the stream, the transform it fell short against, and when it started waiting.
///
/// A wake targets the learner's pipeline rather than a particular task, so a row does not record which task
/// deferred the stream. Nor does it record which records failed or any of the stream's text: a session works
/// that out for the one stream it learns from, and for the other streams it would be out of date as soon as
/// the transform changed.
///
/// A row exists while its stream waits: its task deferred it, and a wake or a person brings it back. Tests
/// keep the rows in memory; a node uses a table.
public interface Deferrals {

    /// Records that the stage has deferred a stream. A second call for the same stream and learner updates
    /// the same row: what it fell short against and why are brought up to date and its attempts are
    /// counted. The time it first waited is kept, since a new reason does not make it any less overdue.
    ///
    /// @param learner        The learner it waits on, whose pipeline is processing it.
    /// @param docUuid        The UUID of the document the learner runs under.
    /// @param inputId        The stream's meta id.
    /// @param feed           Its feed.
    /// @param type           Its stream type.
    /// @param failedAgainst  What the stream was run through and fell short of (the binding's fragment and
    ///                       its version), or null if there was no binding to run it through.
    /// @param reason         Why it waits, in words.
    /// @param failingRecords How many of its records fell short of the bar; zero if there was no binding.
    void deferred(Learner learner,
                  String docUuid,
                  long inputId,
                  String feed,
                  String type,
                  String failedAgainst,
                  String reason,
                  int failingRecords);

    /// Removes a stream's row once it has been served or errored and no longer waits.
    ///
    /// @param learner The learner it waited on.
    /// @param inputId The stream's meta id.
    void released(Learner learner, long inputId);

    /// Removes the rows of every stream waiting on a pipeline's learners, because the pipeline has been
    /// deleted and none of its tasks will run them again.
    ///
    /// @param pipelineUuid The UUID of the deleted pipeline.
    /// @return How many rows were removed.
    int releasedAll(String pipelineUuid);

    /// Gets a page of the streams waiting on one learner, oldest first, for the Supervisor's waiting
    /// streams list.
    ///
    /// @param learner The learner.
    /// @param offset  The first row of the page.
    /// @param limit   The maximum number of rows.
    /// @return The page, and how many streams wait in total.
    Waiting waitingOn(Learner learner, long offset, int limit);

    /// Gets the streams waiting under a document, oldest first.
    ///
    /// @param docUuid The UUID of the document.
    /// @return Its rows.
    List<Deferral> forDocument(String docUuid);

    /// Gets each distinct pair of a learner with a waiting stream and the transform it waits against. The
    /// learning job uses these to find streams that a promotion's wake could not reach (see
    /// [Stage#wakeSuperseded]). There are as many pairs as learners and transforms, not as many as streams.
    ///
    /// Streams waiting for a **first** learning are included, with a null stamp. They have nothing to have
    /// fallen short against, but if they were left out, a stream deferred just after a promotion's wake
    /// would wait the full retry delay for a translation that already exists.
    ///
    /// @return The pairs.
    List<Stamp> stampsWaiting();

    /// Gets one stream's row, if it has one.
    ///
    /// @param learner The learner it waits on.
    /// @param inputId The stream's meta id.
    /// @return Its row, if it is waiting.
    Optional<Deferral> of(Learner learner, long inputId);


    // --------------------------------------------------------------------------------

    /// A page of one learner's waiting streams.
    ///
    /// @param rows  The page's rows, oldest first.
    /// @param total How many wait in all.
    record Waiting(List<Deferral> rows, long total) {

    }

    /// One waiting stream.
    ///
    /// @param learner         The learner it waits on, whose pipeline is processing it.
    /// @param docUuid         The UUID of the document the learner ran under when the stream last waited.
    /// @param inputId         The stream's meta id.
    /// @param feed            Its feed.
    /// @param type            Its stream type.
    /// @param failedAgainst   What it last fell short of, or null if there was no binding.
    /// @param reason          Why it waits.
    /// @param failingRecords  How many records fell short, the last time it was tried.
    /// @param firstDeferredMs When it first waited.
    /// @param attempts        How many times it has been tried and deferred.
    record Deferral(Learner learner,
                    String docUuid,
                    long inputId,
                    String feed,
                    String type,
                    String failedAgainst,
                    String reason,
                    int failingRecords,
                    long firstDeferredMs,
                    int attempts) {

        /// Whether the stream has waited longer than a document's maximum deferral age, which makes it
        /// *raised* (flagged for attention) while it goes on waiting. It is computed when read, because an
        /// age changes continuously and is not worth storing.
        public boolean raised(final long maxAgeMs, final long nowMs) {
            return nowMs - firstDeferredMs > maxAgeMs;
        }

        /// @return The UUID of the pipeline processing the stream, which is the learner's pipeline.
        public String pipeline() {
            return learner.pipelineUuid();
        }

    }


    // --------------------------------------------------------------------------------


    /// A transform a learner's streams wait against.
    ///
    /// @param learner       The learner.
    /// @param failedAgainst The stamp of the transform they fell short of, or **null** if they are waiting
    ///                      for a first learning and so fell short of nothing.
    record Stamp(Learner learner, String failedAgainst) {

    }
}
