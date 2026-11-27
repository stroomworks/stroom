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

import java.util.List;
import java.util.stream.Collectors;

/// Storage for guidance: messages a person leaves for a learner, such as a hint, a correction, or a fact
/// about the feed that the sample does not show. Guidance is included in every question the learner's
/// sessions put to the model.
///
/// Guidance belongs to the **learner**, not to a turn or a session, because what a person knows is about the
/// feed rather than about one particular turn. So nothing has to be timed, nothing is refused for arriving at
/// the wrong moment, and guidance outlives the session that first used it; a relearning months later
/// includes it too.
///
/// Guidance is neither a question nor an answer, and is not a step in the learning flow. It is an input
/// that every question includes.
///
/// Tests keep guidance in memory; a node uses the `learning_flow_guidance` table.
public interface Guidance {

    /// Records guidance a person has left for a learner.
    ///
    /// @param learner The learner it is for.
    /// @param docUuid The UUID of the document the learner runs under, which decides who may see and change
    ///                the guidance.
    /// @param message The guidance text.
    /// @param author  Who wrote it, because a person reading it a year later needs to know whose it was.
    /// @return The row's id, which a turn records to show it included this guidance.
    long given(Learner learner, String docUuid, String message, String author);

    /// Gets all the guidance standing (not withdrawn) for a learner, oldest first, which is the order a
    /// reader wants and the order the model should be given it in.
    ///
    /// @param learner The learner.
    /// @return The standing guidance.
    List<Given> standing(Learner learner);

    /// Withdraws one piece of guidance. Guidance that turns out to be wrong is worse than none, because it
    /// is included in every question the learner asks until it is withdrawn.
    void withdraw(String docUuid, long id);


    // --------------------------------------------------------------------------------


    /// Formats guidance for the system text of a question to the model: oldest first and attributed,
    /// because the model should weigh a person's words as a person's, and because the transcript a person
    /// reads afterwards has to show whose they were. The rebuilt prompt uses the same method, so that it
    /// matches what was sent.
    ///
    /// @param guidance The guidance included, oldest first.
    /// @return The passage, preceded by a blank line; empty if there is no guidance.
    static String asSaid(final List<Given> guidance) {
        if (guidance == null || guidance.isEmpty()) {
            return "";
        }
        return guidance.stream()
                .map(given -> "- " + given.message().strip() + " (" + given.author() + ")")
                .collect(Collectors.joining("\n", "\n\nA supervisor of this feed has said:\n", ""));
    }

    /// One piece of guidance.
    ///
    /// @param id      The id a turn records to show it included this guidance, so that a replayed session
    ///                uses what was actually included rather than what has been added since.
    /// @param message The guidance text, as written.
    /// @param author  Who wrote it.
    /// @param timeMs  When it was given.
    record Given(long id, String message, String author, long timeMs) {

    }
}
