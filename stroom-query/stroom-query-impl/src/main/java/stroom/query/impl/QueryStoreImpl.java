/*
 * Copyright 2017 Crown Copyright
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

package stroom.query.impl;

import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.query.common.v2.QueryDataSourceRemapper;
import stroom.query.shared.QueryDoc;
import stroom.security.api.SecurityContext;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
class QueryStoreImpl
        extends AbstractDocumentStore<QueryDoc>
        implements QueryStore {

    private final QueryDataSourceRemapper queryDataSourceRemapper;

    @Inject
    QueryStoreImpl(final StoreFactory storeFactory,
                   final SecurityContext securityContext,
                   final QuerySerialiser serialiser,
                   final QueryDataSourceRemapper queryDataSourceRemapper) {
        super(storeFactory,
                securityContext,
                serialiser,
                QueryDoc.TYPE,
                QueryDoc::builder,
                QueryDoc::copy);
        this.queryDataSourceRemapper = queryDataSourceRemapper;
    }

    @Override
    protected DependencyRemapFunction<QueryDoc> getDependencyRemapFunction() {
        return (doc, dependencyRemapper) -> doc
                .copy()
                .query(queryDataSourceRemapper.remap(doc.getQuery(), dependencyRemapper))
                .build();
    }
}
