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

package stroom.shapeshifter.ai.stage;

import stroom.pipeline.xml.event.EventList;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.StoredError;

import java.util.List;

/**
 * Everything a scenario asserts on after one run (design 02 §1).
 *
 * @param doc         The document the stage ran under.
 * @param decision    What was decided.
 * @param learner     The learner that met the stream (A114).
 * @param produced    What produced the output (design 01 §7.3 rule 3); null when there is none.
 * @param output      What the bound or promoted fragment produced over the whole stream; null for a
 *                    sentinel or a learner that gave up.
 * @param verdicts    The scorecard's verdicts on the fragment over the whole stream, one per step, in
 *                    chain order; empty when nothing ran.
 * @param transcript  Every exchange with the model, oldest first; empty when it was not consulted.
 * @param events      The same output as the events the fragment emitted making it, for a caller with
 *                    a downstream to play them on to — which is how the supervisor element serves a
 *                    stream without running the fragment a second time. Null where the fragment was
 *                    run by something with no pipeline under it, as Tier 1 runs it, and where
 *                    nothing ran at all.
 * @param diagnostics What the fragment's elements said making that output, for a caller that must
 *                    put them on the pipeline's error stream (A20). A fragment runs under an error
 *                    receiver of its own, so that a candidate's complaints reach the model rather
 *                    than the operator; the one run that is served has to be heard, and these are
 *                    what it said. Empty where nothing ran or nothing is served.
 */
public record StageRun(ShapeshifterAiDoc doc,
                       Decision decision,
                       Learner learner,
                       Produced produced,
                       String output,
                       List<Verdict> verdicts,
                       List<Exchange> transcript,
                       EventList events,
                       List<StoredError> diagnostics) {

}
