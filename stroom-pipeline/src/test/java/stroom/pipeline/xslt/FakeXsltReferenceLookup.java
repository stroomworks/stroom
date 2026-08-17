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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An in-memory {@link XsltReferenceLookup} for tests.
 * <p>
 * Matches names <b>case-sensitively</b> and returns every match, because that is what the store does. A
 * fake that was more forgiving than the real thing would let the parser pass tests it should fail.
 */
class FakeXsltReferenceLookup implements XsltReferenceLookup {

    private final List<DocRef> documents = new ArrayList<>();

    /**
     * Add a document, generating a UUID from its name for legible assertions.
     */
    FakeXsltReferenceLookup with(final String type, final String name) {
        return with(type, name, "uuid-" + name);
    }

    FakeXsltReferenceLookup with(final String type, final String name, final String uuid) {
        documents.add(new DocRef(type, uuid, name));
        return this;
    }

    /**
     * @return true if no documents have been registered, so nothing can resolve.
     */
    boolean isEmpty() {
        return documents.isEmpty();
    }

    @Override
    public List<DocRef> findByName(final String type, final String name) {
        return documents.stream()
                .filter(docRef -> docRef.getType().equals(type) && name.equals(docRef.getName()))
                .toList();
    }

    @Override
    public Optional<DocRef> findByUuid(final String type, final String uuid) {
        return documents.stream()
                .filter(docRef -> docRef.getType().equals(type) && uuid.equals(docRef.getUuid()))
                .findFirst();
    }
}
