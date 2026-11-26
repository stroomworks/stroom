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

package stroom.learningflow.state;

import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Documents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// An in-memory [Documents], from which the background worker reads Learning Flow documents, for tests
/// with no document store.
public final class InMemoryDocuments implements Documents {

    private final Map<String, LearningFlowDoc> documents = new LinkedHashMap<>();

    /// Stores a document, as saving one does.
    public synchronized LearningFlowDoc put(final LearningFlowDoc doc) {
        documents.put(doc.getUuid(), doc);
        return doc;
    }

    @Override
    public synchronized Optional<LearningFlowDoc> byUuid(final String uuid) {
        return Optional.ofNullable(documents.get(uuid));
    }

    /// Removes a document, as deleting one does.
    public synchronized void remove(final String uuid) {
        documents.remove(uuid);
    }
}
