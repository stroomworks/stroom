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

package stroom.shapeshifter.client.component;

/// Something a person can do to a feed on the Feeds tab, whichever of the Supervisor's levels it acts on:
/// its learning run (approve, reject, grant, exhaust, answer), its learner (learn again, stop) or the
/// translation it has (accept, improve, remove, open). Each says what its button says.
public enum FeedAction {
    /// Approves what a run drafted under review mode (A25).
    APPROVE("Approve and use it"),
    /// Rejects what a run drafted (A25); asks why.
    REJECT("Reject…"),
    /// Gives a paused run more tokens or minutes (A77, A145).
    GRANT("Give it more…"),
    /// Sends a paused run to the fallback, the exhaustion step (A65, A76).
    EXHAUST("Finish with Unknowns"),
    /// Answers the question the run stopped at, in place of the AI (A28).
    ANSWER("Answer it myself"),
    /// Accepts a translation on trial now (§6).
    ACCEPT("Accept now"),
    /// Asks for the translation in use to be made better (A46).
    IMPROVE("Improve…"),
    /// Learns afresh from the next stream (A28, A29).
    RELEARN("Learn again"),
    /// Stops learning: the learner gives up (design 06 A115); asks why.
    GIVE_UP("Stop learning…"),
    /// Takes the translation back (A28); asks why.
    RETRACT("Remove translation…"),
    /// Opens the feed's pipeline.
    OPEN_PIPELINE("Open the pipeline"),
    /// Opens the translation's pipeline fragment.
    OPEN_TRANSLATION("Open the translation"),
    /// Opens the document's Setup tab at Learning, where learning is switched on.
    OPEN_SETTINGS("Go to Setup");

    private final String label;

    FeedAction(final String label) {
        this.label = label;
    }

    /// @return What its button says.
    public String getLabel() {
        return label;
    }
}
