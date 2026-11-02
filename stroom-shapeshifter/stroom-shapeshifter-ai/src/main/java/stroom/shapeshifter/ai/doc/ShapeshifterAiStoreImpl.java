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

import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class ShapeshifterAiStoreImpl
        extends AbstractDocumentStore<ShapeshifterAiDoc>
        implements ShapeshifterAiStore {

    @Inject
    public ShapeshifterAiStoreImpl(final StoreFactory storeFactory,
                                      final SecurityContext securityContext,
                                      final ShapeshifterAiSerialiser serialiser) {
        super(storeFactory,
                securityContext,
                serialiser,
                ShapeshifterAiDoc.TYPE,
                ShapeshifterAiDoc::builder,
                ShapeshifterAiDoc::copy);
    }

    /**
     * What the editor cannot check is checked on save, with the fault named, rather than at run time by the
     * supervisor: the flow must be one the stage can hold (A33).
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
        return super.writeDocument(document.copy()
                .flow(document.getFlow().withBuiltInVersion(Templates.VERSION))
                .build());
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
