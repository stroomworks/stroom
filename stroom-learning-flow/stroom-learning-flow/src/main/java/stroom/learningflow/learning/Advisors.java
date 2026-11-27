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

package stroom.learningflow.learning;

import stroom.learningflow.shared.LearningFlowDoc;

/// Supplies the [Advisor] a learning run asks its questions through. There is one per Learning Flow
/// document, because the document names the model to ask and the instructions every question carries.
///
/// In a node the advisor talks to the model the document names; in tests it answers from a script,
/// whatever the document says.
public interface Advisors {

    /// Returns an advisor that asks using the document's templates and instructions as they are now. This is
    /// what a new run uses.
    ///
    /// @param doc The document.
    /// @return The advisor.
    Advisor of(LearningFlowDoc doc);

    /// Returns an advisor that asks using the templates and instructions a run was started with, for
    /// resuming a parked run.
    ///
    /// When a run resumes, the model is shown the turns before it. Those turns were worded with the
    /// templates the run began with, so rendering them from templates someone has edited since, or from
    /// built-in templates an upgrade has changed, would show the model a conversation it never had.
    ///
    /// Where the run's wording was not recorded (attempts opened by a version that did not record it),
    /// implementations fall back to the document's current wording. The default always does.
    ///
    /// @param doc       The document.
    /// @param askedWith The templates and instructions the run was started with.
    /// @return The advisor.
    default Advisor of(final LearningFlowDoc doc, final AskedWith askedWith) {
        return of(doc);
    }
}
