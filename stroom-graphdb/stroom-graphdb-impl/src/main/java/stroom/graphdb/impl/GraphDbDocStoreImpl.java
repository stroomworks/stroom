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

package stroom.graphdb.impl;

import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.StoreFactory;
import stroom.graphdb.shared.GraphDbDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Default {@link GraphDbDocStore}. All behaviour beyond construction is inherited from
 * {@link AbstractDocumentStore}; unlike {@code stroom.planb.impl.PlanBDocStoreImpl} there is no bespoke name
 * validation, since a {@link GraphDbDoc}'s name has no special format requirement.
 */
@Singleton
public class GraphDbDocStoreImpl
        extends AbstractDocumentStore<GraphDbDoc>
        implements GraphDbDocStore {

    /**
     * <b>Preconditions:</b> no parameter is null (enforced by the Guice binding graph supplying them).
     */
    @Inject
    public GraphDbDocStoreImpl(
            final StoreFactory storeFactory,
            final GraphDbDocSerialiser serialiser) {
        super(storeFactory,
                serialiser,
                GraphDbDoc.TYPE,
                GraphDbDoc::builder,
                GraphDbDoc::copy);
    }
}
