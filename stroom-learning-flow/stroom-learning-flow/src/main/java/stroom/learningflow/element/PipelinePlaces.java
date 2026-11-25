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

package stroom.learningflow.element;

import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.shared.ExplorerNode;
import stroom.learningflow.element.LearnerDocuments.Element;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Places;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/// The place of a learner's content in a node (design 06 A113): the element's folder and name properties,
/// read from its pipeline as merged, so that a value a factory wrote, or a parent pipeline set, counts.
/// Unset, the folder is the pipeline's own and the name is the pipeline's with ` learned` after it (A133):
/// beside the pipeline and sorting with it, but never the pipeline's own name.
public class PipelinePlaces implements Places {

    /// The element's property for the folder what it learns goes in: the name Stroom gives
    /// [LearningFlowParser#setLearnedFolder].
    static final String FOLDER_PROPERTY = "learnedFolder";
    /// The element's property for the name what it learns is given: the name Stroom gives
    /// [LearningFlowParser#setLearnedName].
    static final String NAME_PROPERTY = "learnedName";
    /// Where content goes for a learner whose pipeline cannot be read — deleted while a session it
    /// opened was still being carried on — so that what was learned can still be found.
    private static final DocPath ORPHANED = DocPath.fromParts("Learning Flow");
    /// What follows the pipeline's name in a default name: the pipeline is in the same folder under its
    /// own name, and what it learns is not the pipeline.
    private static final String LEARNED = " learned";

    private final LearnerDocuments learnerDocuments;
    private final Provider<ExplorerNodeService> explorerNodeServiceProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    public PipelinePlaces(final LearnerDocuments learnerDocuments,
                          final Provider<ExplorerNodeService> explorerNodeServiceProvider,
                          final Provider<SecurityContext> securityContextProvider) {
        this.learnerDocuments = learnerDocuments;
        this.explorerNodeServiceProvider = explorerNodeServiceProvider;
        this.securityContextProvider = securityContextProvider;
    }

    @Override
    public Place of(final Learner learner) {
        final Optional<Element> element = learnerDocuments.elementOf(learner);
        if (element.isEmpty()) {
            return new Place(ORPHANED, learner.pipelineUuid());
        }
        final PipelineDoc pipeline = element.get().pipeline();
        final DocPath folder = element.get().text(FOLDER_PROPERTY)
                .map(PipelinePlaces::folder)
                .orElseGet(() -> beside(pipeline));
        final String name = element.get().text(NAME_PROPERTY)
                .orElseGet(() -> pipeline.getName() + LEARNED);
        return new Place(folder, name);
    }

    /// A folder as a person or a factory pattern writes it, `Feeds/DOOR-ACCESS`: a leading `/` or none,
    /// and space around a `/` or an empty part between two, which a pattern substituting to nothing can
    /// leave, taken as not meant. Nothing left is the top of the tree.
    private static DocPath folder(final String text) {
        return DocPath.fromParts(Arrays.stream(text.split("/"))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList());
    }

    /// The folder the pipeline is in, from the top of the tree. Read as the processing user, as the
    /// pipeline was: where a learner's content goes is not a question about who is asking.
    private DocPath beside(final PipelineDoc pipeline) {
        return securityContextProvider.get().asProcessingUserResult(() -> {
            final ExplorerNodeService explorerNodeService = explorerNodeServiceProvider.get();
            final String root = explorerNodeService.getRoot().getUuid();
            final List<String> folders = explorerNodeService.getPath(pipeline.asDocRef()).stream()
                    .filter(node -> !root.equals(node.getUuid()))
                    .map(ExplorerNode::getName)
                    .toList();
            return DocPath.fromParts(folders);
        });
    }
}
