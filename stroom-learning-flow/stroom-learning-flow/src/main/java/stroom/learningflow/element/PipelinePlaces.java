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

/// Works out where a learner's content is written on a node: the folder and name set by the Smart
/// Pipeline element's `learnedFolder` and `learnedName` properties. They are read from the pipeline merged
/// with its parents, so a value written by a Pipeline Factory or set on a parent pipeline counts. If unset,
/// the folder is the pipeline's own and the name is the pipeline's name followed by ` learned`, so the
/// content sits beside the pipeline and sorts with it, but never has the pipeline's own name.
public class PipelinePlaces implements Places {

    /// The name of the element property for the folder learned content goes in, which is the property
    /// name Stroom derives from [LearningFlowParser#setLearnedFolder].
    static final String FOLDER_PROPERTY = "learnedFolder";
    /// The name of the element property for the name learned content is given, which is the property name
    /// Stroom derives from [LearningFlowParser#setLearnedName].
    static final String NAME_PROPERTY = "learnedName";
    /// Where content goes for a learner whose pipeline cannot be read (e.g. it was deleted while one of its
    /// sessions was still running), so that what was learned can still be found.
    private static final DocPath ORPHANED = DocPath.fromParts("Learning Flow");
    /// The suffix added to the pipeline's name to make the default name. The pipeline is in the same
    /// folder under its own name, so learned content needs a different one.
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

    /// Parses a folder path as a person or a Pipeline Factory pattern writes it, e.g. `Feeds/DOOR-ACCESS`.
    /// A leading `/` is optional, and whitespace around a `/` or an empty part between two (which a pattern
    /// that substitutes to nothing can leave) is ignored. A path with no parts left is the top of the tree.
    private static DocPath folder(final String text) {
        return DocPath.fromParts(Arrays.stream(text.split("/"))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList());
    }

    /// The folder the pipeline is in, as a path from the top of the tree. It is read as the processing
    /// user, as the pipeline was, because where a learner's content goes does not depend on who is asking.
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
