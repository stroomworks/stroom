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

package stroom.learningflow.client.component;

/// An action a person can take on a Smart Pipeline from the Learning Status tab. An action applies to one of
/// three things: the current learning run (approve, reject, give more, finish with Unknowns, answer), the
/// learner (learn again, stop learning) or the translation in use (improve, remove). Go to Setup opens the
/// document instead. Each action holds its button's label.
public enum FeedAction {
    /// Approves the translation a run drafted, when the document requires new translations to be approved.
    APPROVE("Approve and use it"),
    /// Rejects the translation a run drafted; asks why.
    REJECT("Reject…"),
    /// Gives a paused run more tokens or minutes. The extra applies to that run only, not to the document.
    GRANT("Give it more…"),
    /// Sends a paused run to the fallback step, which learns to mark the records still not translated as
    /// Unknown events. A run that runs out of budget pauses for a person rather than doing this itself.
    EXHAUST("Finish with Unknowns"),
    /// Answers the question the run stopped at, in place of the AI.
    ANSWER("Answer it myself"),
    /// Asks for the translation in use to be improved.
    IMPROVE("Improve…"),
    /// Learns a new translation from scratch, starting from the next stream.
    RELEARN("Learn again"),
    /// Stops learning: the learner gives up, and streams nothing can translate fail with the reason given;
    /// asks why.
    GIVE_UP("Stop learning…"),
    /// Removes the translation in use; asks why.
    RETRACT("Remove translation…"),
    /// Opens the Learning Flow's Setup tab at the AI model and instructions section, where learning is
    /// switched on.
    OPEN_SETTINGS("Go to Setup");

    private final String label;

    FeedAction(final String label) {
        this.label = label;
    }

    /// @return The label on the action's button.
    public String getLabel() {
        return label;
    }
}
