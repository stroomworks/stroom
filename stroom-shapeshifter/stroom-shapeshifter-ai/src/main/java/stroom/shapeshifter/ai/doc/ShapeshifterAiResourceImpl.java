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

package stroom.shapeshifter.ai.doc;

import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.BuiltInTemplates;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// The document's own resource: reading and writing it, and the built-in templates its Learn tab starts
/// from. What a learner has learned under it is the learner's (design 06 phase J slice 2b), and a person
/// acts on that from the Supervisor.
@AutoLogged
public class ShapeshifterAiResourceImpl implements ShapeshifterAiResource {

    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;

    @Inject
    ShapeshifterAiResourceImpl(final Provider<ShapeshifterAiStore> storeProvider,
                               final Provider<DocumentResourceHelper> documentResourceHelperProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
    }

    @Override
    public ShapeshifterAiDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(
                storeProvider.get(),
                ShapeshifterAiDoc.buildDocRef().uuid(uuid).build());
    }

    @Override
    public BuiltInTemplates templates() {
        return new BuiltInTemplates(Templates.VERSION, Templates.builtIns());
    }

    @Override
    public ShapeshifterAiDoc update(final String uuid, final ShapeshifterAiDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }
}
