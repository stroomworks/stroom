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

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowElements;
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.learningflow.stage.Stage;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.factory.HasStepDetails;
import stroom.pipeline.factory.PipelineProperty;
import stroom.pipeline.factory.PipelinePropertyDocRef;
import stroom.pipeline.parser.AbstractParser;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.state.MetaData;
import stroom.svg.shared.SvgImage;

import jakarta.inject.Inject;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;


/// The Smart Pipeline pipeline element. It sits in the parser position, is given the raw stream, and
/// emits events. Each instance in a pipeline is a learner. What it does with each stream is decided by the
/// [Stage], through [Supervision]: it serves the stream through the learner's binding; defers the stream
/// when nothing can serve it yet, leaving the learning to the background worker; or, once the learner has
/// given up, logs an error naming the learner and the reason.
///
/// A bound fragment runs as a nested pipeline, built by the same `stroom.pipeline.factory.PipelineFactory`
/// in the same pipeline scope, so its errors reach this pipeline's error stream and its events reach
/// this element's targets. The fragment is run only once per stream: the run that is judged is the run
/// that is served. The bindings that produced the output are recorded in the output stream's attributes
/// through [MetaData].
@ConfigurableElement(
        type = LearningFlowParser.TYPE,
        displayValue = "Smart Pipeline",
        category = Category.PARSER,
        description = """
                A supervised stage over a raw stream: runs each stream through the fragment this \
                element's learner is bound to, learns a fragment when nothing is bound, and refuses \
                streams once its learner has given up.
                """,
        roles = {
                PipelineElementType.ROLE_PARSER,
                PipelineElementType.ROLE_HAS_TARGETS,
                PipelineElementType.VISABILITY_SIMPLE,
                PipelineElementType.VISABILITY_STEPPING,
                PipelineElementType.ROLE_MUTATOR},
        icon = SvgImage.AI)
public class LearningFlowParser extends AbstractParser implements HasStepDetails {

    public static final String TYPE = LearningFlowElements.PARSER;

    private final Supervision supervision;

    private DocRef docRef;
    private boolean asProcessed;

    @Inject
    public LearningFlowParser(final ErrorReceiverProxy errorReceiverProxy,
                                final LocationFactoryProxy locationFactory,
                                final Supervision supervision) {
        super(errorReceiverProxy, locationFactory);
        this.supervision = supervision;
    }

    @PipelineProperty(description = "The Learning Flow that governs this stage.", displayPriority = 1)
    @PipelinePropertyDocRef(types = LearningFlowDoc.TYPE)
    public void setLearningFlow(final DocRef docRef) {
        this.docRef = docRef;
    }

    /// Sets how a reprocess translates each stream. False (the default) serves with the learner's binding
    /// as it is today, which is what is wanted when re-running a backlog after a fix. True runs each
    /// stream through the snapshot of the fragment that produced its earlier output, which is what an
    /// audit wants: the configuration cannot have changed, so neither can the result.
    ///
    /// It is a property rather than part of a reprocess request because a reprocess in Stroom names a
    /// pipeline, not a mode. An operator who wants to re-run history exactly as it happened points a
    /// pipeline whose Smart Pipeline element has this set at the streams in question.
    @PipelineProperty(
            description = "Process each stream through the fragment that produced it before, rather "
                          + "than through whatever the learner is bound to today.",
            defaultValue = "false",
            displayPriority = 2)
    public void setAsProcessed(final boolean asProcessed) {
        this.asProcessed = asProcessed;
    }

    /// Sets the time zone of timestamps that carry no offset, e.g. `Europe/London`. A stream's own
    /// `TimeZone` header takes precedence; where neither is set, times with no offset are read as UTC.
    /// The resolved zone goes into the stream's metadata as `ResolvedTimeZone`, which the learned
    /// stylesheet reads with `stroom:meta` and passes to `stroom:format-date`. This means changing the
    /// zone needs no relearning, and the model is never asked to assume a zone.
    ///
    /// The property is declared here so that the pipeline editor and a Pipeline Factory can set it. During
    /// learning it is read from the pipeline by [PipelineZones], because the background worker runs no
    /// pipeline and so has no element to receive it.
    @PipelineProperty(
            description = "The time zone of timestamps that carry no offset, e.g. Europe/London. A "
                          + "stream's own TimeZone header beats this; where neither is set, UTC.",
            displayPriority = 5)
    public void setTimeZone(final String timeZone) {
        // A serving run gets the zone here. A run judged by the background worker has no element, and
        // reads it from the pipeline through PipelineZones.
        supervision.setTimeZone(timeZone);
    }

    /// Sets the folder that this element's learned content is written to, as a path from the top of the
    /// tree, e.g. `Feeds/DOOR-ACCESS`. If unset, content goes in the pipeline's own folder.
    ///
    /// The property is declared here so that the pipeline editor and a Pipeline Factory can set it. It is
    /// read from the pipeline by [PipelinePlaces], because the background worker runs no pipeline and so
    /// has no element to receive it.
    @PipelineProperty(
            description = "The folder what this stage learns goes in, e.g. Feeds/DOOR-ACCESS. Unset, the "
                          + "pipeline's own folder.",
            displayPriority = 3)
    public void setLearnedFolder(final String learnedFolder) {
        // Read from the pipeline by PipelinePlaces, not kept here.
    }

    /// Sets the name given to this element's learned fragment. Its text converter and XSLT take the same
    /// name with the element id appended, and a later fragment takes the name followed by the first free
    /// number. If unset, the pipeline's name followed by ` learned` is used. Like the folder, it is read
    /// from the pipeline by [PipelinePlaces].
    @PipelineProperty(
            description = "The name what this stage learns is given. Unset, the pipeline's name followed "
                          + "by \"learned\".",
            displayPriority = 4)
    public void setLearnedName(final String learnedName) {
        // Read from the pipeline by PipelinePlaces, not kept here.
    }

    @Override
    protected XMLReader createReader() {
        supervision.checkPosition(supervision.document(docRef, getElementId()), getElementId());
        return new SupervisorReader();
    }


    /// What this element decided about the record just captured, for the stepper's Smart Pipeline pane.
    @Override
    public LearningFlowStepDetails getStepDetails(final long recordIndex) {
        return supervision.stepDetails(getElementId(), recordIndex);
    }


    // --------------------------------------------------------------------------------


    /// The parser's XML reader, which hands the stream to [Supervision]. The Learning Flow document is only
    /// read, never written: it holds only what a person authors, and what is learned is stored in database
    /// rows and content documents.
    private final class SupervisorReader extends stroom.pipeline.xml.converter.AbstractParser {

        @Override
        public void parse(final InputSource inputSource) throws SAXException {
            supervision.startRecord(getElementId());
            supervision.supervise(supervision.document(docRef, getElementId()), getElementId(), asProcessed,
                    inputSource, getContentHandler());
        }
    }

}
