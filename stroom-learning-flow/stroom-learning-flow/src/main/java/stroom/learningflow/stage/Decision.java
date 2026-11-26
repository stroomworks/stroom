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

package stroom.learningflow.stage;

import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.StopReport;
import stroom.util.shared.StoredError;

import java.util.List;

/// What the stage decided to do with one stream: serve it, promote or keep a binding, write a draft, give
/// up, error it, park the session, defer it, or (in a dry run) what it would have done.
public sealed interface Decision {

    /// The learner's binding served the stream: it was processed with the bound fragment, without any
    /// learning.
    record Bound(Binding binding) implements Decision {

    }

    /// A fragment was learned and promoted, and this binding now serves the learner with it.
    record Promoted(Binding binding, double score) implements Decision {

    }

    /// A learner marked for relearning was relearned while the serving binding served the stream, and the
    /// candidate beat it. The binding is rebound to the new fragment for the streams that follow. It keeps
    /// the same `uuid`, and its regression corpus grows.
    record Rebound(Binding incumbent, Binding binding, double score) implements Decision {

    }

    /// A learner marked for relearning was relearned while the serving binding served the stream, and the
    /// serving binding was kept, because no candidate passed or the candidate did not beat it on this
    /// stream or on its regression corpus. Nothing was written.
    record Kept(Binding incumbent, String reason) implements Decision {

    }

    /// The document uses review promotion mode, so a candidate that would have been bound was written as
    /// a draft binding that serves nothing until a person approves or rejects it. For a learner with no
    /// serving binding, the stream waits for the draft and is woken when it is approved. For a relearning,
    /// the serving binding served the stream and goes on serving until the draft is decided.
    record Drafted(Binding binding, double score) implements Decision {

    }

    /// No fragment could be learned, so the learner is recorded as given up. The diagnostics are the last
    /// candidate's: they are reported beside the reason on the error output, and are what a person reads to
    /// see why the model failed.
    ///
    /// @param report The reason broken into parts, if the session stopped with a report; null otherwise.
    record GivenUp(String reason, List<StoredError> diagnostics, StopReport report) implements Decision {

        public GivenUp {
            diagnostics = List.copyOf(diagnostics);
        }

        public GivenUp(final String reason, final List<StoredError> diagnostics) {
            this(reason, diagnostics, null);
        }
    }

    /// The stream was not processed and the model was not asked, because the learner has given up or the
    /// session the worker was resuming could go no further. The stream is given a sentinel: an error stream
    /// with the reason, and no other output.
    record Sentinel(String reason) implements Decision {

    }

    /// The session stopped before its stream cleared the bar (for example because its budget ran out) and
    /// is parked for a person. It keeps its claim on the learner with a lease that does not expire, and its
    /// streams go on waiting. Running out of budget never makes records become marked Unknowns.
    ///
    /// @param reason Why it stopped, in one line.
    /// @param report The reason broken into parts; null if none was produced.
    record Parked(String reason, StopReport report) implements Decision {

        public Parked(final String reason) {
            this(reason, null);
        }
    }

    /// The stream waits: nothing is emitted, nothing is errored, and its processor task ends `DEFERRED`, to
    /// be tried again when the learner's binding changes or the retry delay passes. A deferral row records
    /// what it is waiting on. If a session was needed and none held the learner's claim, one was opened and
    /// left for the worker.
    ///
    /// @param reason Why it waits, in words.
    record Deferred(String reason) implements Decision {

    }

    /// What the stage *would* have done, which is all a dry run (stepping) may report. Stepping serves and
    /// does nothing else, so a learner with no serving binding is reported here rather than learned for,
    /// errored or given up.
    ///
    /// @param what What would happen, in the words the stage would have used had it done it.
    record Would(String what) implements Decision {

        /// The decision as a person reads it, e.g. *Would learn a fragment for this learner*, so that every
        /// screen words it the same way and only a hypothetical decision reads as one.
        public String said() {
            return what == null || what.isEmpty()
                    ? "Would do nothing"
                    : "Would " + Character.toLowerCase(what.charAt(0)) + what.substring(1);
        }
    }
}
