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

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.learning.FlowExamples;
import stroom.shapeshifter.ai.learning.TargetSchemas;
import stroom.shapeshifter.ai.learning.TargetStructure;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.BuiltInFlow;
import stroom.shapeshifter.shared.BuiltInTemplates;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.TargetSchemaSummary;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.PermissionException;
import stroom.xmlschema.shared.XmlSchemaDoc;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/// The document's own resource: reading and writing it, and the built-in templates its Setup tab starts
/// from. What a learner has learned under it is the learner's (design 06 phase J slice 2b), and a person
/// acts on that from the Supervisor.
@AutoLogged
public class ShapeshifterAiResourceImpl implements ShapeshifterAiResource {

    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<TargetSchemas> targetSchemasProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    ShapeshifterAiResourceImpl(final Provider<ShapeshifterAiStore> storeProvider,
                               final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                               final Provider<TargetSchemas> targetSchemasProvider,
                               final Provider<SecurityContext> securityContextProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.targetSchemasProvider = targetSchemasProvider;
        this.securityContextProvider = securityContextProvider;
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
    public List<BuiltInFlow> flowExamples() {
        return FlowExamples.all();
    }

    @Override
    public TargetSchemaSummary targetSchema(final String uuid) {
        final DocRef docRef = new DocRef(XmlSchemaDoc.TYPE, uuid);
        final SecurityContext securityContext = securityContextProvider.get();
        if (!securityContext.hasDocumentPermission(docRef, DocumentPermission.USE)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to use this schema");
        }
        return targetSchemasProvider.get().resolve(docRef)
                .map(target -> new TargetSchemaSummary(target.name(), target.isEventLogging(),
                        target.rootElement(), TargetStructure.rootChildren(target)))
                .orElse(null);
    }

    @Override
    public ShapeshifterAiDoc update(final String uuid, final ShapeshifterAiDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }
}
