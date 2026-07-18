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

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentStore;
import stroom.graphdb.shared.GraphDbDoc;

import java.util.List;

/**
 * The {@link stroom.docstore.api.Store}-backed document store for {@link GraphDbDoc}. Note: creating a document
 * through this store provisions the metadata record only — the internal physical stores ({@link GraphStores}) are
 * provisioned separately, keyed by the document's UUID, so that the two lifecycles (document metadata vs. the
 * owned LMDB stores) can be reasoned about independently while still always changing together in practice.
 */
public interface GraphDbDocStore extends DocumentStore<GraphDbDoc> {

    /**
     * @return every {@link GraphDbDoc} in the store, as a {@link DocRef}.
     */
    List<DocRef> list();
}
