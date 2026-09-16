/*
 * Copyright 2025 Crown Copyright
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

package stroom.pathways.impl;

import stroom.docstore.api.DocumentNotFoundException;
import stroom.pathways.shared.AddPathway;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.UpdatePathway;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class PathwaysService {

    private final PathwaysStore pathwaysStore;
    private final ShardedPathwayReader shardedPathwayReader;

    @Inject
    public PathwaysService(final ShardedPathwayReader shardedPathwayReader,
                           final PathwaysStore pathwaysStore) {
        this.shardedPathwayReader = shardedPathwayReader;
        this.pathwaysStore = pathwaysStore;
    }

    public PathwayResultPage findPathways(final FindPathwayCriteria criteria) {
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(criteria.getDataSourceRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(criteria.getDataSourceRef());
        }

        // The model lives on the shared file store, split by operation name, so any node can answer
        // from it. Nothing has to work out which node holds it, and nothing has to be asked.
        return shardedPathwayReader.findPathways(pathwaysDoc, criteria);
    }

    public Boolean addPathway(final AddPathway addPathway) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public Boolean updatePathway(final UpdatePathway updatePathway) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public Boolean deletePathway(final DeletePathway deletePathway) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
