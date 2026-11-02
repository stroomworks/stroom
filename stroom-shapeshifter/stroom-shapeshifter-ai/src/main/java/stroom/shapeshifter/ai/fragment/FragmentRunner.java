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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.xml.event.EventList;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.shared.RecordBoundary;

import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Runs a written fragment over an input and says what each of its elements made of it: what the
/// promotion gate judges (§7.4), and what a bound stream goes through.
///
/// Two implementations, and the difference matters. [PipelineFragmentRunner] runs the fragment as a
/// pipeline — the real elements, the real pools, the real filters — and is what a node uses, so that
/// what is judged is what will run. [StandInFragmentRunner] walks the chain with the module's step
/// runners instead, which needs no node, no stores beyond the content ones and no pipeline scope, and is
/// what the Tier 1 scenarios run on (design 02 §2).
///
/// They are meant to agree, and where they do not the pipeline is right. A Tier 2 scenario holds them to
/// the same events on the same fragment and input, because a stand-in that has drifted from the thing it
/// stands in for is worse than no stand-in: it would pass a candidate the pipeline refuses.
public interface FragmentRunner {

    /// @param boundary What one record is in the stream (A35), as the binding carries it, for the scorers
    ///                 that count records; null where none was settled.
    /// @return One attempted step per element of the chain, in chain order. A step that produced nothing
    /// leaves the later ones unrun; the caller sees that from the last step's result.
    List<Attempted> run(DocRef fragment, String input, RecordBoundary boundary);

    default List<Attempted> run(final DocRef fragment, final String input) {
        return run(fragment, input, null);
    }

    /// The same, with each element given the code of a snapshot in place of the document it references
    /// (A70): how an as-processed reprocess runs what produced an output, after the fragment has moved
    /// on in place.
    ///
    /// @param code Element id to the code it is to run with; an element not named runs what it
    ///             references.
    default List<Attempted> run(final DocRef fragment,
                                final String input,
                                final RecordBoundary boundary,
                                final Map<String, String> code) {
        if (!code.isEmpty()) {
            throw new UnsupportedOperationException("This runner runs what a fragment references and takes no code");
        }
        return run(fragment, input, boundary);
    }

    /// Serve a stream (design 04 §3, *streams of any size*): run the fragment over it as it is read, judge
    /// each record of the tail's output as it is produced, hand each that clears the bar on, and stop at
    /// the first that does not. Nothing larger than a record is held. The stream is read once.
    ///
    /// @param fragment   The fragment.
    /// @param input      The stream, as bytes, UTF-8.
    /// @param boundary   What one record is in the stream (A35), as the binding carries it; null where none
    ///                   was settled.
    /// @param judge      The bar, record by record.
    /// @param downstream Where records that clear the bar go; null where nothing is to be handed on.
    /// @return What the run came to.
    default ServedStream serve(final DocRef fragment,
                               final InputStream input,
                               final RecordBoundary boundary,
                               final RecordJudge judge,
                               final ContentHandler downstream) {
        throw new UnsupportedOperationException("This runner judges a run and does not serve one");
    }

    /// The events the chain's tail emitted on the run just made, where the runner has a pipeline under
    /// it to emit them. This is how a stream's output reaches the supervisor's own downstream without
    /// the fragment being run a second time: the run that is judged is the run that is served.
    ///
    /// A runner keeps one run's events and the next run replaces them, so a caller that wants a
    /// particular run's output takes it immediately after that run. The stage does: it takes them where
    /// it builds the judgement they belong to, and carries them with it, because which run is the one
    /// being served is not known until the decision is made — a candidate that fails the floor is run
    /// and discarded, and its events must not be the ones played on.
    ///
    /// Empty from [StandInFragmentRunner], which has no pipeline and no events: Tier 1 asserts on what
    /// each step wrote and has no downstream to play anything on to.
    default Optional<EventList> lastOutput() {
        return Optional.empty();
    }

    /// What each of the fragment's own elements was given and wrote on the run just made, a list per
    /// record in record order (A30, design 01 §11.7).
    ///
    /// This is what lets a learned chain be stepped. A supervised stage looks, from the outside, like
    /// one element that takes a stream and emits events; everything that decided the shape of them —
    /// the parser's records, the transform's output — happens inside it and is invisible. The stepper
    /// shows these beneath the stage, so that `DSParser → XSLTFilter` steps like any other pair.
    ///
    /// **Taken**, not read: the capture is handed over once and the runner is left holding nothing.
    ///
    /// That is the whole of the method's safety. A stage decides many things without running a fragment
    /// at all — a draft awaiting review, a learner given up, a learner nothing serves — and
    /// a caller that merely *read* the last capture would be handed the previous stream's chain and
    /// show it as this one's. Taking it means the second ask finds nothing, which is the truth.
    ///
    /// Empty from [StandInFragmentRunner], which walks the chain rather than running a pipeline and so
    /// has no elements to name. Tier 1 asserts on what each step wrote; the stepper is Tier 2's.
    default List<FragmentRecord> takeRecords() {
        return List.of();
    }


    // --------------------------------------------------------------------------------


    /// What the fragment's elements made of one record of the stream, in chain order.
    ///
    /// The ids are namespaced by the stage that ran them before they reach a person, because a fragment
    /// may hold an `XSLTFilter` and so may the pipeline it is running inside; here they are the
    /// fragment's own.
    record FragmentRecord(List<NestedElementData> elements) {

    }
}
