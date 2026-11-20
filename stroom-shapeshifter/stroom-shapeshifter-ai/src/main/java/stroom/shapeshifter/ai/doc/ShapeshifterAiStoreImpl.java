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
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.shapeshifter.ai.learning.FlowExamples;
import stroom.shapeshifter.ai.learning.TargetSchema;
import stroom.shapeshifter.ai.learning.TargetSchemas;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.CheckSettings;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class ShapeshifterAiStoreImpl
        extends AbstractDocumentStore<ShapeshifterAiDoc>
        implements ShapeshifterAiStore {

    private final SecurityContext securityContext;
    private final TargetSchemas targetSchemas;

    @Inject
    public ShapeshifterAiStoreImpl(final StoreFactory storeFactory,
                                   final SecurityContext securityContext,
                                   final ShapeshifterAiSerialiser serialiser,
                                   final TargetSchemas targetSchemas) {
        super(storeFactory,
                securityContext,
                serialiser,
                ShapeshifterAiDoc.TYPE,
                ShapeshifterAiDoc::builder,
                ShapeshifterAiDoc::copy);
        this.securityContext = securityContext;
        this.targetSchemas = targetSchemas;
    }

    /// The setup a new Learning Flow starts from.
    ///
    /// `DIRECT` for now: on the feeds measured so far it reached the same scores at half the tokens
    /// (design 02 §6.3). Decision N3 makes it `ESCALATING`, which is a change of this one name once a
    /// `TestLiveScenarios` run against a live model has confirmed it (Track 2 step 1c).
    static final FlowExample DEFAULT_EXAMPLE = FlowExample.DIRECT;

    /// A new Learning Flow, with the steps of [#DEFAULT_EXAMPLE] and the latest event-logging schema
    /// installed as its target (task 7, decision D1).
    ///
    /// The built-in setups are JSON on the server now (Track 2 step 1c), so a document built in shared
    /// code cannot carry one: `ShapeshifterAiDoc` starts with no steps, and this is what gives a new
    /// document a flow it can actually learn by. It has to happen here, because [#writeDocument] refuses
    /// a flow the stage cannot hold (A33) and a flow with no steps is one of those — a document created
    /// without this could not be saved.
    ///
    /// The target schema is settled here for the same reason it is saved rather than looked up each
    /// time: installing a newer event-logging later must not change what a document that already exists
    /// writes. Every question the document asks names this schema, so a document and its prompts always
    /// agree; a node with no event-logging installed gives a document no target, and the readiness check
    /// is what says so.
    ///
    /// @param name The document's name.
    /// @return The new document.
    @Override
    public DocRef createDocument(final String name) {
        final DocRef docRef = super.createDocument(name);
        writeDocument(readDocument(docRef).copy()
                .flow(FlowExamples.flow(DEFAULT_EXAMPLE))
                .targetSchema(targetSchemas.latestEventLogging().map(TargetSchema::docRef).orElse(null))
                .build());
        return docRef;
    }

    /**
     * What the editor cannot check is checked on save, with the fault named, rather than at run time by the
     * supervisor: the flow must be one the stage can hold (A33), and the checks must keep to their rules
     * (the one model of checks: compile always a gate, nothing turned on that isn't built, thresholds from 0
     * to 1).
     */
    @Override
    public ShapeshifterAiDoc writeDocument(final ShapeshifterAiDoc document) {
        // A33: a flow the stage cannot hold is refused here, naming what is wrong, rather than found when
        // the first stream arrives; and the document is stamped with the built-in text it was saved against.
        final List<String> problems = new ArrayList<>(document.getFlow().problems());
        problems.addAll(Templates.problems(document.getFlow()));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The flow cannot be held: " + String.join("; ", problems));
        }
        final List<String> checkProblems = CheckSettings.problems(document.getChecks());
        if (!checkProblems.isEmpty()) {
            throw new IllegalArgumentException("The checks cannot be held: " + String.join(" ", checkProblems));
        }
        refuseUnpermittedAutomatic(document);
        return super.writeDocument(document.copy()
                .flow(document.getFlow().withBuiltInVersion(Templates.VERSION))
                .build());
    }

    /// Switching a Learning Flow to *use new translations as soon as they pass* needs
    /// `APPROVE_SMART_PIPELINES_AUTOMATICALLY` (SIM-1, decided 19 November 2026).
    ///
    /// Approving one translation needs EDIT on its pipeline, so the pipeline's owner decides. Setting the
    /// mode to automatic approves **every future translation, for every feed this Learning Flow serves,
    /// with nobody looking** — a wider act than editing the Learning Flow, and one no existing permission
    /// fits: OWNER usually belongs to whoever created the document, and ADMINISTRATOR is too coarse.
    ///
    /// Checked here rather than in the editor so that it holds **on import** too, as Content Templates
    /// check their run-as user. Only the move *to* automatic is checked: switching back to waiting for
    /// approval is the safer direction and needs only EDIT, and someone without the permission can still
    /// edit the rest of a Learning Flow that is already automatic.
    ///
    /// @param document The document as it is to be saved.
    private void refuseUnpermittedAutomatic(final ShapeshifterAiDoc document) {
        if (document.getPromotionMode() != PromotionMode.AUTOMATIC
            || securityContext.hasAppPermission(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY)) {
            return;
        }
        final boolean wasAutomatic = NullSafe.getOrElse(readDocumentQuietly(document.getUuid()),
                ShapeshifterAiDoc::getPromotionMode, PromotionMode.REVIEW) == PromotionMode.AUTOMATIC;
        if (!wasAutomatic) {
            throw new PermissionException(securityContext.getUserRef(),
                    "Using new translations as soon as they pass approves every future translation for "
                    + "every feed this Learning Flow serves, with nobody checking them. That needs the '"
                    + AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY.getDisplayValue()
                    + "' permission.");
        }
    }

    /// The document as it is stored, or null where there is none to read — a new document, or one whose
    /// read is refused. A document that cannot be read was not already automatic as far as this caller is
    /// concerned, which is the safe reading.
    ///
    /// @param uuid The document's uuid.
    /// @return It, or null.
    private ShapeshifterAiDoc readDocumentQuietly(final String uuid) {
        try {
            return uuid == null
                    ? null
                    : readDocument(ShapeshifterAiDoc.buildDocRef().uuid(uuid).build());
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A Shapeshifter AI document depends on its model document, and on nothing else: since A41 the bindings
     * are rows and the fragments are named by them, not by the document, so an exported document
     * carries its configuration and none of what it learned — as a processor filter's state is not part of
     * the pipeline it runs. Declaring the model here is what makes a content-pack import re-point it at the
     * imported copy, what lets a copied document follow it, and what draws the edge in the explorer's
     * dependency view; the inherited null would silently disable all three.
     */
    @Override
    protected DependencyRemapFunction<ShapeshifterAiDoc> getDependencyRemapFunction() {
        return (doc, remapper) -> {
            final ShapeshifterAiDoc.Builder builder = doc.copy();
            if (doc.getModel() != null) {
                builder.model(remapper.remap(doc.getModel()));
            }
            return builder.build();
        };
    }
}
