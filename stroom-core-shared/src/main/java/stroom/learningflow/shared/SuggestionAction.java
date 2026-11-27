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

/// What a [Suggestion] can be acted on with, where a screen offers it as a button or a link.
public enum SuggestionAction {
    /// Give the paused run more time or tokens.
    GIVE_MORE,
    /// Answer the question it stopped at.
    ANSWER,
    /// Leave the AI a note about the feed.
    LEAVE_NOTE,
    /// Finish with marked Unknowns.
    FINISH_WITH_UNKNOWNS,
    /// Learn the feed again from its next stream.
    LEARN_AGAIN,
    /// Change the limits on a learning run: tries, time, tokens and sample size.
    CHANGE_LIMITS,
    /// Change the learning steps or the element types.
    CHANGE_STEPS,
    /// Change the model the document uses.
    CHANGE_MODEL,
    /// Open the model's own document, e.g. for its timeout or key.
    OPEN_MODEL
}
