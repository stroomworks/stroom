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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Documents;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// The documents of A28 in memory, for a harness with no document store.
public final class InMemoryDocuments implements Documents {

    private final Map<String, ShapeshifterAiDoc> documents = new LinkedHashMap<>();

    /// Keep a document, as saving one does.
    public synchronized ShapeshifterAiDoc put(final ShapeshifterAiDoc doc) {
        documents.put(doc.getUuid(), doc);
        return doc;
    }

    @Override
    public synchronized Optional<ShapeshifterAiDoc> byUuid(final String uuid) {
        return Optional.ofNullable(documents.get(uuid));
    }

    /// Forget a document, as deleting one does.
    public synchronized void remove(final String uuid) {
        documents.remove(uuid);
    }
}
