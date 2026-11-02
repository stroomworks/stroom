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

package stroom.pipelinefactory.impl;

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerService;
import stroom.pipelinefactory.shared.PipelineFactoryCombination;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/// What a Pipeline Factory's screen asks of what the factory has met (design 06 §3.5): the list, and
/// forgetting one. Reading it needs VIEW on the factory and forgetting needs EDIT, as reading and
/// changing the factory itself do.
public class PipelineFactoryService {

    private final Combinations combinations;
    private final ExplorerService explorerService;
    private final SecurityContext securityContext;

    @Inject
    PipelineFactoryService(final Combinations combinations,
                           final ExplorerService explorerService,
                           final SecurityContext securityContext) {
        this.combinations = combinations;
        this.explorerService = explorerService;
        this.securityContext = securityContext;
    }

    /// @param factoryUuid The factory.
    /// @return Every combination it has met, oldest first, each saying whether its pipeline still exists.
    public List<PipelineFactoryCombination> combinations(final String factoryUuid) {
        require(factoryUuid, DocumentPermission.VIEW);
        return combinations.list(factoryUuid).stream()
                .map(this::shown)
                .toList();
    }

    /// @param factoryUuid The factory.
    /// @param values      The combination's key values.
    /// @return Whether the factory had met it.
    public boolean forget(final String factoryUuid, final Map<String, String> values) {
        require(factoryUuid, DocumentPermission.EDIT);
        return combinations.forget(factoryUuid, new Combination(values));
    }

    private void require(final String factoryUuid, final DocumentPermission permission) {
        final DocRef factory = PipelineFactoryDoc.buildDocRef().uuid(factoryUuid).build();
        if (!securityContext.hasDocumentPermission(factory, permission)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to " + permission.getDisplayValue().toLowerCase()
                    + " this Pipeline Factory");
        }
    }

    private PipelineFactoryCombination shown(final MetCombination met) {
        // Whether a pipeline is there is a fact about the pipeline, not about who is asking: a pipeline
        // this person may not see is still there, and still stops the factory making another.
        final boolean exists = met.pipeline() != null
                               && securityContext.asProcessingUserResult(() ->
                explorerService.getFromDocRef(met.pipeline()).isPresent());
        return new PipelineFactoryCombination(met.combination().values(),
                met.state().name(),
                met.tries(),
                met.pipeline(),
                exists,
                met.filterUuid(),
                met.message(),
                met.createTimeMs(),
                met.updateTimeMs());
    }
}
