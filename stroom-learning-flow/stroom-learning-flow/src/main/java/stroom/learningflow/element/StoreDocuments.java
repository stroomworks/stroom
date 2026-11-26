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

package stroom.learningflow.element;

import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Documents;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;

/// The node's implementation of [Documents], which the background worker uses to read the Learning Flow
/// document an attempt names. It reads the document's current version from the document store by UUID.
@Singleton
public class StoreDocuments implements Documents {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StoreDocuments.class);

    private final LearningFlowStore store;

    @Inject
    public StoreDocuments(final LearningFlowStore store) {
        this.store = store;
    }

    @Override
    public Optional<LearningFlowDoc> byUuid(final String uuid) {
        try {
            return Optional.ofNullable(store.readDocument(LearningFlowDoc.buildDocRef().uuid(uuid).build()));
        } catch (final RuntimeException e) {
            // The document was deleted or this node cannot read it; either way the attempt cannot continue.
            LOGGER.warn(() -> LogUtil.message("Learning Flow document {} could not be read: {}",
                    uuid, e.getMessage()), e);
            return Optional.empty();
        }
    }
}
