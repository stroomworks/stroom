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

/// Puts the stream a session is being judged on into the pipeline's scoped state, so that the Stroom
/// functions a learned translation may use return values for *that* stream.
///
/// A judged run on a node is a real pipeline run, so `stroom:format-date` takes the year missing from a
/// timestamp from `MetaHolder`, and `stroom:meta` reads `MetaDataHolder`. Outside a processing task, which
/// is where the worker runs, nothing sets either, and `format-date` then falls back to the current year.
/// A stream back-loaded from an archive would then be judged against today's date but served against its
/// own, so judging and serving would disagree about what the translation does.
///
/// **It must be set for every session, not once per pass.** The worker resumes a batch of sessions in one
/// pipeline scope ([stroom.learningflow.element.DeferredLearning]), and the holders are scoped to it, so a
/// session that did not replace the previous session's stream would silently use the previous stream's year.
///
/// The same call resolves the stream's time zone and puts it in the metadata as `ResolvedTimeZone`, so that
/// a learned stylesheet reads the same zone while it is judged as it will while it serves.
public interface StreamHeld {

    /// An implementation that does nothing, for the test harness, which has no pipeline state and runs the
    /// module's own stand-in functions rather than Stroom's.
    StreamHeld NONE = (metaId, learner) -> {
    };

    /// Holds the stream, replacing whatever the scope held before.
    ///
    /// @param metaId  The stream's meta id.
    /// @param learner The learner the session is for. Its pipeline has the `timeZone` property used for a
    ///                stream that does not state its own time zone.
    void hold(long metaId, Learner learner);
}
