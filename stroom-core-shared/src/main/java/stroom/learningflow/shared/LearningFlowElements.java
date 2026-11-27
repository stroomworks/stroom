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

package stroom.learningflow.shared;

/// Names of the Smart Pipeline element type and its document property, shared so that the client can
/// recognise the element. The stepper shows a Learning Flow pane in place of this element's code pane, and it
/// cannot be told at runtime which elements need that.
public final class LearningFlowElements {

    /// The element type of the Smart Pipeline element, which sits in a pipeline where a parser sits.
    public static final String PARSER = "LearningFlow";

    /// The element's property naming the Learning Flow document it learns under, i.e. the property name Stroom
    /// derives from the element's `setLearningFlow` method. The server reads it to join a learner to its
    /// document, and the pipeline editor reads it to find the documents a pipeline's Smart Pipeline elements
    /// learn under.
    public static final String DOCUMENT_PROPERTY = "learningFlow";

    private LearningFlowElements() {
    }
}
