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

/// Keeps the document recorded on a learner's rows up to date with the document the learner runs under.
///
/// A learner's rows (its bindings, its state, its guidance and the streams waiting on it) belong to the
/// learner, and each also records the document the learner runs under, because that is what the
/// Supervisor lists by and what decides who may see and act on a row.
///
/// A pipeline's element can be pointed at another document, and what the learner learned goes with it. The
/// rows are therefore moved to the new document when the learner is first seen under it. Otherwise the new
/// document's Supervisor would not show what serves its pipeline, and the old document's editors could
/// still retract it.
public interface Learners {

    /// Records that the learner now runs under this document, moving every row of the learner that records
    /// another document to this one. This is called for every stream a task decides, so an implementation
    /// should remember what it has already done and make the call cheap when nothing has changed.
    ///
    /// @param learner The learner.
    /// @param docUuid The UUID of the document it runs under now.
    void runsUnder(Learner learner, String docUuid);
}
