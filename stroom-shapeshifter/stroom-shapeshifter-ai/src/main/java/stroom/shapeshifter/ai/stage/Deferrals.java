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

package stroom.shapeshifter.ai.stage;

import java.util.List;
import java.util.Optional;

/// The streams that are waiting, and on what (design 04 §3, §6): one row per stream a learner has put
/// back — the stream, the transform it failed against, and since when. A wake names the learner's
/// pipeline, not a row's task (A129), so a row does not say which task deferred it. Nothing about which
/// of its records failed and none of its text (A69): the session works that out for the one stream it
/// learns from, and for the rest it would be stale the moment the transform moved.
///
/// A row is a stream that waits: its task deferred it, and a wake or a person brings it back (design 04
/// §3, §5.3). In-memory in scenarios; a table in a node.
public interface Deferrals {

    /// A stream the stage would put back, or has. A second call for the same stream and learner is the
    /// same row, with what it failed against and why brought up to date and its attempts counted; the
    /// time it first waited is kept, since a new reason does not make it newly late.
    ///
    /// @param learner        The learner it waits on, whose pipeline is processing it.
    /// @param docUuid        The document the learner runs under.
    /// @param inputId        The stream's meta id.
    /// @param feed           Its feed.
    /// @param type           Its type.
    /// @param failedAgainst  What the stream was run through and fell short of: the binding's fragment and
    ///                       its version, or null where there was no binding to run it through.
    /// @param reason         Why it waits, in words.
    /// @param failingRecords How many of its records fell short of the bar; zero where there was no binding.
    void deferred(Learner learner,
                  String docUuid,
                  long inputId,
                  String feed,
                  String type,
                  String failedAgainst,
                  String reason,
                  int failingRecords);

    /// The stream has been served, or errored, and waits no more.
    ///
    /// @param learner The learner it waited on.
    /// @param inputId The stream's meta id.
    void released(Learner learner, long inputId);

    /// Every stream waiting on a pipeline's learners waits no more: the pipeline has been deleted, and no
    /// task of it will run them again (design 06 A135).
    ///
    /// @param pipelineUuid The pipeline.
    /// @return How many rows went.
    int releasedAll(String pipelineUuid);

    /// A page of what waits on one learner, oldest first: its pipeline's waiting streams tab (design 06
    /// A115).
    ///
    /// @param learner The learner.
    /// @param offset  The first row of the page.
    /// @param limit   How many rows.
    /// @return The page, and how many wait in all.
    Waiting waitingOn(Learner learner, long offset, int limit);

    /// What is waiting for a document, oldest first.
    ///
    /// @param docUuid The document.
    /// @return Its rows.
    List<Deferral> forDocument(String docUuid);

    /// The learners with a stream waiting against a transform, and the transforms they wait against, each
    /// pair once: what the learning job sweeps for streams a promotion's wake could not reach (A130, see
    /// [Stage#wakeSuperseded]). As many as there are learners and transforms, not streams.
    ///
    /// @return The pairs.
    List<Stamp> stampsWaiting();

    /// One stream's row, where it has one.
    ///
    /// @param learner The learner it waits on.
    /// @param inputId The stream's meta id.
    /// @return Its row.
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
    /// @param docUuid         The document the learner ran under when the stream last waited.
    /// @param inputId         The stream's meta id.
    /// @param feed            Its feed.
    /// @param type            Its type.
    /// @param failedAgainst   What it last fell short of, or null where there was no binding.
    /// @param reason          Why it waits.
    /// @param failingRecords  How many records fell short, the last time it was tried.
    /// @param firstDeferredMs When it first waited.
    /// @param attempts        How many times it has been tried and put back.
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

        /// Whether the stream has waited past a document's maximum deferral age (A57, A78): raised, and
        /// still waiting (A79). Computed when read, since age is not a thing to store.
        public boolean raised(final long maxAgeMs, final long nowMs) {
            return nowMs - firstDeferredMs > maxAgeMs;
        }

        /// @return The pipeline processing the stream: the learner's.
        public String pipeline() {
            return learner.pipelineUuid();
        }

    }


    // --------------------------------------------------------------------------------


    /// A transform a learner's streams wait against.
    ///
    /// @param learner       The learner.
    /// @param failedAgainst The stamp of the transform they fell short of.
    record Stamp(Learner learner, String failedAgainst) {

    }
}
