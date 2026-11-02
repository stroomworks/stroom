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

package stroom.shapeshifter.ai.element;

import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.stage.Documents;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;

/// The Shapeshifter AI documents of A28 in a node: the document store, read by uuid, as it stands now.
@Singleton
public class StoreDocuments implements Documents {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StoreDocuments.class);

    private final ShapeshifterAiStore store;

    @Inject
    public StoreDocuments(final ShapeshifterAiStore store) {
        this.store = store;
    }

    @Override
    public Optional<ShapeshifterAiDoc> byUuid(final String uuid) {
        try {
            return Optional.ofNullable(store.readDocument(ShapeshifterAiDoc.buildDocRef().uuid(uuid).build()));
        } catch (final RuntimeException e) {
            // Deleted, or this node may not read it: either way the attempt cannot be carried on.
            LOGGER.warn(() -> LogUtil.message("Shapeshifter AI document {} could not be read: {}",
                    uuid, e.getMessage()), e);
            return Optional.empty();
        }
    }
}
