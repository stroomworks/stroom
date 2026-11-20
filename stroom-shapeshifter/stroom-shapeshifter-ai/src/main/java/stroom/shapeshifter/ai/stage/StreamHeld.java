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

/// Puts the stream an attempt is being judged on into the pipeline's state, so that the Stroom functions
/// a learned translation may use answer for *that* stream (assumption AS6).
///
/// A judged run on a node is a real pipeline run, so `stroom:format-date` takes the year a timestamp
/// leaves out from `MetaHolder`, and `stroom:meta` reads `MetaDataHolder`. Outside a processing task —
/// which is where the worker runs — nothing sets either, and `format-date` then falls back to the clock.
/// That is the fault of Track 1 task 3: a stream back-loaded from an archive would be judged against today
/// and served against its own time, so judging and serving would disagree about what the translation does.
///
/// **It must be set for every attempt, not once per pass.** The worker carries a batch of attempts in one
/// pipeline scope ([stroom.shapeshifter.ai.element.DeferredLearning]), and the holders are scoped to it,
/// so an attempt that did not replace the previous one's would silently take the previous stream's year.
///
/// The same run resolves the stream's time zone and puts it in the metadata as `ResolvedTimeZone`
/// (decision N6), so that a learned stylesheet reads the same zone while it is judged as it will
/// while it serves.
public interface StreamHeld {

    /// A stage with no pipeline state to hold: the scenario harness, which runs the module's own stand-in
    /// functions rather than Stroom's.
    StreamHeld NONE = (metaId, learner) -> {
    };

    /// Holds the stream, replacing whatever the scope held before.
    ///
    /// @param metaId  The stream's meta id.
    /// @param learner Whose attempt this is: its pipeline carries the `timeZone` property that a stream
    ///                saying nothing about its own zone falls back to (decision N6).
    void hold(long metaId, Learner learner);
}
