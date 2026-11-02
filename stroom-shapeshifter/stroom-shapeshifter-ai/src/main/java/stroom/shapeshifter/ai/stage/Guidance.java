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

import java.util.List;

/// What a supervisor has told a learner (ruling A46, design 01 §12 item 29): a hint, a correction, or a
/// fact about the feed that the sample does not show.
///
/// It attaches to the **learner** (A128), not to a turn and not to an
/// attempt, and that is the whole of A46's first decision. What a person knows is about the feed, not
/// about turn 7 of attempt 412 — so nothing has to be timed, nothing is refused for arriving at the
/// wrong moment, and a hint outlives the attempt that first used it. The relearning of A29, months
/// later, carries it too.
///
/// It is neither a question nor an answer. The flow's grammar (A37) is untouched: guidance is an input
/// every question carries, and a question kind it is not.
///
/// In-memory in scenarios; the {@code shapeshifter_guidance} table in a node.
public interface Guidance {

    /// Record what a person has said about what a learner learns.
    ///
    /// @param learner The learner it is about.
    /// @param docUuid The document the learner runs under, which decides who may see and change it.
    /// @param message What was said.
    /// @param author  Who said it. A person reading a hint a year later needs to know whose it was.
    /// @return The row's id, which is what a turn records having carried.
    long given(Learner learner, String docUuid, String message, String author);

    /// Everything standing for a learner, oldest first — which is the order a reader wants and the order
    /// a model should be told them in.
    ///
    /// @param learner The learner.
    /// @return What has been said.
    List<Given> standing(Learner learner);

    /// Take one back. A hint that turned out to be wrong is worse than no hint: it is carried into every
    /// question the learner asks from then on.
    void withdraw(String docUuid, long id);


    // --------------------------------------------------------------------------------


    /// One thing a supervisor said.
    ///
    /// @param id      What a turn records having carried (A45), so that a re-walk replays what was
    ///                actually used rather than what has since been added.
    /// @param message Their words, as they wrote them.
    /// @param author  Whose they are.
    /// @param timeMs  When they were given.
    record Given(long id, String message, String author, long timeMs) {

    }
}
