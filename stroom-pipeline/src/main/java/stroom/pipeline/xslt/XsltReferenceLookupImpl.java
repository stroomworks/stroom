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

package stroom.pipeline.xslt;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The production {@link XsltReferenceLookup}, a thin adapter over {@link DocFinder}.
 * <p>
 * Thin on purpose. {@link DocFinder} is what both runtimes use - {@code CustomURIResolver} for imports
 * and {@code Dictionary} by way of {@code DictionaryStoreImpl} - so going through it is what makes the
 * parser agree with the runtime about which documents a name matches, including the case sensitivity and
 * the read-permission filtering that come with it.
 */
@Singleton
class XsltReferenceLookupImpl implements XsltReferenceLookup {

    private final DocFinder docFinder;

    @Inject
    XsltReferenceLookupImpl(final DocFinder docFinder) {
        this.docFinder = Objects.requireNonNull(docFinder, "Null docFinder supplied");
    }

    @Override
    public List<DocRef> findByName(final String type, final String name) {
        Objects.requireNonNull(type, "Null type supplied");
        Objects.requireNonNull(name, "Null name supplied");
        // allowWildCards = false, so '*' in a name is matched literally rather than as a pattern. A
        // stylesheet naming a document is naming one document, not a set.
        return docFinder.findByName(type, name, false);
    }

    @Override
    public Optional<DocRef> findByUuid(final String type, final String uuid) {
        Objects.requireNonNull(type, "Null type supplied");
        Objects.requireNonNull(uuid, "Null uuid supplied");
        return docFinder.decorateIfExists(new DocRef(type, uuid));
    }
}
