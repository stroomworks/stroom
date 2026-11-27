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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.learning.TargetStructure;
import stroom.learningflow.learning.Templates;
import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.BuiltInTemplates;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowResource;
import stroom.learningflow.shared.TargetSchemaSummary;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.PermissionException;
import stroom.xmlschema.shared.XmlSchemaDoc;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/// The REST resource for the Learning Flow document itself: reading and writing it, and the built-in
/// templates, example flows and target schemas its editor uses.
///
/// What a learner has learned under the document is stored against the learner rather than the document,
/// so a person acts on it from the Supervisor's resource rather than this one.
@AutoLogged
public class LearningFlowResourceImpl implements LearningFlowResource {

    private final Provider<LearningFlowStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<TargetSchemas> targetSchemasProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    LearningFlowResourceImpl(final Provider<LearningFlowStore> storeProvider,
                               final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                               final Provider<TargetSchemas> targetSchemasProvider,
                               final Provider<SecurityContext> securityContextProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.targetSchemasProvider = targetSchemasProvider;
        this.securityContextProvider = securityContextProvider;
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public LearningFlowDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(
                storeProvider.get(),
                LearningFlowDoc.buildDocRef().uuid(uuid).build());
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public BuiltInTemplates fetchTemplates() {
        return new BuiltInTemplates(Templates.VERSION, Templates.builtIns());
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public List<BuiltInFlow> flowExamples() {
        return FlowExamples.all();
    }

    @Override
    @AutoLogged(OperationType.VIEW)
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
    @AutoLogged(OperationType.UPDATE)
    public LearningFlowDoc update(final String uuid, final LearningFlowDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }
}
