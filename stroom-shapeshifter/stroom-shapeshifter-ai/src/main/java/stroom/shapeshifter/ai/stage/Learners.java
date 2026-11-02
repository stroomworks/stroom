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

/// Which document a learner's rows are kept under (design 06 phase J slice 2b). What a learner keeps is
/// its own — its bindings, its state, what has been said to it and the streams
/// waiting on it — and each row records the document the learner runs under beside it, since that is
/// what the Supervisor lists by and what decides who may see and act on a row.
///
/// A pipeline's element can be pointed at another document. What the learner learned goes with it, so
/// the rows are brought to the new document when the learner is first met under it: left where they
/// were, the new document's Supervisor would not show what serves its pipeline, and the old document's
/// editors could still retract it.
public interface Learners {

    /// The learner runs under this document now: every row it keeps that records another document is
    /// brought to this one. Called for every stream a task decides, so an implementation remembers what
    /// it has already done and makes this cheap when nothing has changed.
    ///
    /// @param learner The learner.
    /// @param docUuid The document it runs under.
    void runsUnder(Learner learner, String docUuid);
}
