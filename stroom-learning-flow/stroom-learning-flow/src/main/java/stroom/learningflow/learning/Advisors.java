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

/**
 * Where a stage gets its {@link Advisor}: one per document, since the document names the model to ask
 * (design 01 §10) and the instructions every question carries. A node answers with the model the
 * document names; a scenario answers with its script whatever the document.
 */
public interface Advisors {

    /// An advisor that asks in the document's words as they stand now: what a run opening for the first
    /// time is asked with.
    ///
    /// @param doc The document.
    /// @return How to ask the model.
    Advisor of(LearningFlowDoc doc);

    /// An advisor that asks in the words a run was asked with (decision N9), for carrying a parked run
    /// on. A session is a conversation: every turn before this one was put in the words of the templates
    /// the run began with, and the model is shown its own history as it resumes. Rendering that history
    /// from templates somebody has edited since — or from built-ins an upgrade has moved — would show
    /// the model a conversation it never had.
    ///
    /// Falls back to the document's words where the run's were not recorded, which is every attempt
    /// opened before they were kept.
    ///
    /// @param doc       The document.
    /// @param askedWith The words the run was asked with.
    /// @return How to ask the model.
    default Advisor of(final LearningFlowDoc doc, final AskedWith askedWith) {
        return of(doc);
    }
}
