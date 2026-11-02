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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
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
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiElements;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.svg.shared.SvgImage;

import jakarta.inject.Inject;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;


/**
 * The supervisor at the extraction stage (design 01 §3, §12 item 4): one supervised stage fed the raw
 * stream, emitting events. Sits where a parser sits, and does what the {@link Stage} decides — runs
 * the stream through a bound fragment, learns one when nothing is bound and the document allows, or
 * refuses it with an error naming the learner and the reason (A4). What it does is {@link Supervision}'s.
 * <p>
 * A bound fragment runs as a nested pipeline (A20), built by the same {@link stroom.pipeline.factory.PipelineFactory}
 * in the same pipeline scope, so its errors reach this pipeline's error stream and its events reach this
 * element's targets. It runs once: the run the stage judged is the run that is served. The bindings that
 * produced the output (§7.3 rule 3) go into the output stream's attributes through {@link MetaData}.
 * <p>
 * The stage's runtime state — learners, outputs, deferrals, regression set — is whatever the node
 * binds for the seams of A26; until the module of §12 item 8 exists that is in-memory and node-local.
 */
@ConfigurableElement(
        type = ShapeshifterAiParser.TYPE,
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
public class ShapeshifterAiParser extends AbstractParser implements HasStepDetails {

    public static final String TYPE = ShapeshifterAiElements.PARSER;

    private final Supervision supervision;

    private DocRef docRef;
    private boolean asProcessed;

    @Inject
    public ShapeshifterAiParser(final ErrorReceiverProxy errorReceiverProxy,
                                final LocationFactoryProxy locationFactory,
                                final Supervision supervision) {
        super(errorReceiverProxy, locationFactory);
        this.supervision = supervision;
    }

    @PipelineProperty(description = "The Shapeshifter AI document that governs this stage.", displayPriority = 1)
    @PipelinePropertyDocRef(types = ShapeshifterAiDoc.TYPE)
    public void setShapeshifterAi(final DocRef docRef) {
        this.docRef = docRef;
    }

    /// Which question a reprocess is asking (design 01 §7.3). False — the default — serves with the
    /// learner's binding as it stands today, which is what "we have fixed it, run the backlog again" wants
    /// and the mode a release (A12) reprocesses in. True runs each stream through the fragment that
    /// produced its output before, which is what an audit wants: the content cannot have changed, so
    /// the answer cannot either.
    ///
    /// It is a property rather than something a reprocess request carries because a reprocess in Stroom
    /// names a pipeline and not a mode; an operator who wants to re-run history as it happened points a
    /// pipeline whose supervisor has this set at the streams in question.
    @PipelineProperty(
            description = "Process each stream through the fragment that produced it before, rather "
                          + "than through whatever the learner is bound to today.",
            defaultValue = "false",
            displayPriority = 2)
    public void setAsProcessed(final boolean asProcessed) {
        this.asProcessed = asProcessed;
    }

    /// Where what this stage learns goes (design 06 A113): a folder path from the top of the tree, e.g.
    /// `Feeds/DOOR-ACCESS`, which a Pipeline Factory writes from its pattern. Unset, beside the pipeline.
    ///
    /// Declared here so that the pipeline editor and a factory can set it; it is read from the pipeline by
    /// [PipelinePlaces], since the worker that carries a session on runs no pipeline and has no element to
    /// be told it by.
    @PipelineProperty(
            description = "The folder what this stage learns goes in, e.g. Feeds/DOOR-ACCESS. Unset, the "
                          + "pipeline's own folder.",
            displayPriority = 3)
    public void setLearnedFolder(final String learnedFolder) {
        // Read from the pipeline by PipelinePlaces, not kept here.
    }

    /// What what this stage learns is called (design 06 A113): the fragment's name, which its text
    /// converter and XSLT take with the element id appended, and a later fragment takes with the first
    /// free number after it (A132). Unset, the pipeline's name with ` learned` after it (A133). Read from
    /// the pipeline, as the folder is.
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


    /// What this stage decided about the record just captured, for the stepper's stage pane (A30).
    @Override
    public ShapeshifterAiStepDetails getStepDetails(final long recordIndex) {
        return supervision.stepDetails(getElementId(), recordIndex);
    }


    // --------------------------------------------------------------------------------


    /**
     * Reads the stream and lets the supervision decide. The document is never written: it holds only
     * what a person authors, and what the stage learns is rows (A41). Two tasks learning for the same learner
     * at once still race until the lease of A42 exists, but they race on rows and not on one document.
     */
    private final class SupervisorReader extends stroom.pipeline.xml.converter.AbstractParser {

        @Override
        public void parse(final InputSource inputSource) throws SAXException {
            supervision.startRecord(getElementId());
            supervision.supervise(supervision.document(docRef, getElementId()), getElementId(), asProcessed,
                    inputSource, getContentHandler());
        }
    }

}
