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

package stroom.learningflow.fragment;

import stroom.docref.DocRef;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.shared.RecordBoundary;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.xml.event.EventList;

import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Runs a stored fragment over an input and reports what each of its elements made of it. The promotion
/// gate uses it to judge a candidate fragment, and the Smart Pipeline element uses it to serve streams
/// with a binding.
///
/// There are two implementations. [PipelineFragmentRunner] runs the fragment as a real pipeline, with
/// the real elements, pools and filters, and is what a node uses, so that what is judged is what will
/// run. `StandInFragmentRunner`, a test fixture, walks the chain with the module's step runners instead.
/// It needs no node, no stores beyond the content stores and no pipeline scope, and is used by the
/// Tier 1 (module-only) scenario tests.
///
/// The two are meant to agree, and where they do not, the pipeline runner is correct. A Tier 2 scenario
/// test checks that they produce the same events for the same fragment and input, because a stand-in that
/// has drifted from the real thing is worse than none: it would pass a candidate the pipeline refuses.
public interface FragmentRunner {

    /// Runs a fragment over the whole of an input held in memory, for judging.
    ///
    /// @param boundary What one record is in the stream, as the binding records it, for the scorers that
    ///                 count records; null where none was settled.
    /// @return One attempted step per scored element of the chain, in chain order. A step that produced
    /// nothing leaves the later ones unrun; the caller sees that from the last step's result.
    List<Attempted> run(DocRef fragment, String input, RecordBoundary boundary);

    /// The same as [#run(DocRef, String, RecordBoundary)], but with elements given code from a snapshot
    /// in place of the documents they reference. This is how an as-processed reprocess runs the exact
    /// configuration that produced an output, after the fragment's documents have since been changed.
    ///
    /// @param code A map from element id to the code it is to run with; an element not in the map runs
    ///             the document it references.
    default List<Attempted> run(final DocRef fragment,
                                final String input,
                                final RecordBoundary boundary,
                                final Map<String, String> code) {
        if (!code.isEmpty()) {
            throw new UnsupportedOperationException("This runner runs what a fragment references and takes no code");
        }
        return run(fragment, input, boundary);
    }

    /// Serves a stream of any size. The fragment is run over the stream as it is read, each record of the
    /// chain's output is judged as it is produced, each record that clears the bar is passed on, and the
    /// run stops at the first record that does not. Nothing larger than a record is held in memory, and
    /// the stream is read once.
    ///
    /// @param fragment   The fragment.
    /// @param input      The stream, as UTF-8 bytes.
    /// @param boundary   What one record is in the stream, as the binding records it; null where none was
    ///                   settled.
    /// @param judge      Applies the bar to each record.
    /// @param downstream Where records that clear the bar go; null where nothing is to be passed on.
    /// @return The outcome of the run.
    default ServedStream serve(final DocRef fragment,
                               final InputStream input,
                               final RecordBoundary boundary,
                               final RecordJudge judge,
                               final ContentHandler downstream) {
        throw new UnsupportedOperationException("This runner judges a run and does not serve one");
    }

    /// The events the end of the chain emitted on the latest run, where the runner runs a real pipeline.
    /// This is how a stream's output reaches the Smart Pipeline element's own downstream without the
    /// fragment being run a second time: the run that is judged is the run that is served.
    ///
    /// A runner keeps only the latest run's events, so a caller that wants a particular run's output must
    /// take it immediately after that run. The stage does this when it builds the judgement the events
    /// belong to, and keeps them with it, because which run will be served is not known until the decision
    /// is made. For example, a candidate that fails the floor is run and discarded, and its events must
    /// not be the ones passed on.
    ///
    /// `StandInFragmentRunner` returns empty, because it has no pipeline and so no events; Tier 1 tests
    /// check what each step wrote and have no downstream.
    default Optional<EventList> lastOutput() {
        return Optional.empty();
    }

    /// What each of the fragment's own elements was given and wrote on the latest run, as one entry per
    /// record in record order.
    ///
    /// This is what lets a learned chain be stepped. From the outside, the Smart Pipeline element looks
    /// like one element that takes a stream and emits events; the parser's records and the transform's
    /// output are produced inside it and would otherwise be invisible. The stepper shows these records
    /// beneath the element, so that `DSParser → XSLTFilter` steps like any other pair of elements.
    ///
    /// The records are **taken**, not just read: they are returned once and the runner is left holding
    /// nothing. This matters because a stage makes many decisions without running a fragment at all (a
    /// draft awaiting review, a learner that has given up, a learner nothing serves), and a caller that
    /// merely read the last capture would be given the previous stream's chain and show it as this one's.
    /// Taking them means a second call correctly finds nothing.
    ///
    /// `StandInFragmentRunner` returns empty, because it walks the chain rather than running a pipeline
    /// and so has no elements to name. Stepping is only tested in Tier 2.
    default List<FragmentRecord> takeRecords() {
        return List.of();
    }


    // --------------------------------------------------------------------------------


    /// What the fragment's elements made of one record of the stream, in chain order.
    ///
    /// The element ids here are the fragment's own. They are prefixed with the id of the Smart Pipeline
    /// element before they are shown to a person, because a fragment may hold an `XSLTFilter` and so may
    /// the pipeline it is running inside.
    record FragmentRecord(List<NestedElementData> elements) {

    }
}
