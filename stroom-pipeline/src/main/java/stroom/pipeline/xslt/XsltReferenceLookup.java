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

import java.util.List;
import java.util.Optional;

/**
 * The only way {@link XsltReferenceParser} reaches the document store.
 * <p>
 * Deliberately narrow, for two reasons. It keeps the parser free of side effects - it can look documents
 * up but can do nothing else - and it lets the parser be tested against a fake with no database, which
 * matters because the interesting behaviour is in how names resolve rather than in where they resolve
 * from.
 * <p>
 * Both methods mirror the runtime exactly, and callers must not soften them. Names are matched
 * <b>case-sensitively</b> because the store does: {@code DBPersistence.find} compares
 * {@code name COLLATE utf8mb4_0900_as_cs}.
 */
public interface XsltReferenceLookup {

    /**
     * Find documents of the given type whose name matches exactly.
     * <p>
     * Names are not unique for a type - the explorer permits duplicates in different folders - so this
     * may return more than one document. The parser does not choose between them.
     *
     * @param type The document type. Must not be null.
     * @param name The name to match, exactly and case-sensitively. Must not be null.
     * @return the matching documents, possibly empty, never null.
     */
    List<DocRef> findByName(String type, String name);

    /**
     * Find a document of the given type by UUID.
     *
     * @param type The document type. Must not be null.
     * @param uuid The UUID to look for. Must not be null. This may be any string, since it comes from the
     *             stylesheet - a value that is not a UUID simply matches nothing.
     * @return the document, or empty if there is none.
     */
    Optional<DocRef> findByUuid(String type, String uuid);
}
