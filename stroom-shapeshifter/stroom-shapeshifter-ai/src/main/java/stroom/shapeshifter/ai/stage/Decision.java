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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.shared.Binding;
import stroom.util.shared.StoredError;

import java.util.List;

/**
 * What the stage decided about one stream, in the terms of design 02 §4.
 */
public sealed interface Decision {

    /**
     * The learner's binding served the stream: it was processed with the bound fragment, no learning.
     */
    record Bound(Binding binding) implements Decision {

    }

    /**
     * A fragment was learned and promoted, and this binding now serves the learner with it.
     */
    record Promoted(Binding binding, double score) implements Decision {

    }

    /**
     * A learner marked for relearning (A29) was relearned while the incumbent served the stream, and
     * the candidate beat it: the binding is rebound to the new fragment (design 01 §7.3 rule 2) for the
     * streams that follow. Same binding, same {@code uuid}; the regression set grows.
     */
    record Rebound(Binding incumbent, Binding binding, double score) implements Decision {

    }

    /**
     * A learner marked for relearning was relearned while the incumbent served the stream, and the
     * incumbent was kept: no candidate passed, or the candidate did not beat it on this stream or on the
     * regression set (A15, A18). Nothing was written.
     */
    record Kept(Binding incumbent, String reason) implements Decision {

    }

    /**
     * The document is in review mode (A25): a candidate that would have been bound is written as a draft
     * binding that serves nothing, and waits for Approve or Reject. For a learner nothing served the stream
     * waits behind the draft, woken when it is approved; for a relearning one the incumbent served the
     * stream and the draft waits behind it.
     */
    record Drafted(Binding binding, double score) implements Decision {

    }

    /**
     * A variant cleared the floor on the learner's records but there were too few for a held-out split
     * (A14), so it is bound provisionally (A5, design 01 §6): it handles this stream and every one after
     * it, marked as such, until the learner has {@code required} records and the gate can be met.
     */
    record Provisional(Binding binding, double score, int records, int required) implements Decision {

    }

    /**
     * No fragment passed within the budget; the learner is recorded as given up. The diagnostics are the
     * last candidate's — what the sentinel names beside the reason (design 02 §4 step 5), and what a person
     * reads to see why the model could not.
     */
    record GivenUp(String reason, List<StoredError> diagnostics) implements Decision {

        public GivenUp {
            diagnostics = List.copyOf(diagnostics);
        }

    }

    /**
     * The stream was not processed and the model was not consulted: the learner has given up, or the
     * attempt the worker carried could go no further. The stream is sentinelled — an error stream, and
     * nothing else (A4, A131).
     */
    record Sentinel(String reason) implements Decision {

    }

    /// The session stopped short of its stream clearing the bar — out of budget, or out of candidates —
    /// and parked for a person (A65): its claim held with a lease that does not lapse, its streams
    /// waiting, nothing baked as Unknown because money ran out.
    ///
    /// @param reason What it stopped on.
    record Parked(String reason) implements Decision {

    }

    /// The stream waits (design 04 §3): nothing is emitted, nothing is errored, and the task that carries
    /// it ends deferred, to be tried again when the learner's transform moves or the retry delay passes. A
    /// deferral row says what it waits on; where a session was needed and none held the claim, one was
    /// opened and parked for the worker.
    ///
    /// @param reason Why it waits, in words.
    record Deferred(String reason) implements Decision {

    }

    /// What the stage *would* have done, which is all a dry run may say (A30, design 01 §11.7).
    /// Stepping serves and does nothing else, so a learner no binding serves is reported here
    /// rather than learned, sentinelled or given up.
    ///
    /// @param what What would happen, in the words the stage would have used had it done it.
    record Would(String what) implements Decision {

        /// The clause as a person reads it — *would learn a fragment for this learner* — so that every
        /// surface says it the same way and only a decision that is hypothetical reads as one.
        public String said() {
            return what == null || what.isEmpty()
                    ? "Would do nothing"
                    : "Would " + Character.toLowerCase(what.charAt(0)) + what.substring(1);
        }
    }
}
