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
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.memory.MemoryPersistence;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.mock.MockSecurityContext;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;

import java.util.List;

/**
 * What the document store tests share: a store over the real {@code StoreImpl} and in-memory persistence,
 * and a document with a model, a binding bound to a fragment and a binding with nothing promoted yet, so that
 * every kind of dependency the store must remap — and the absence of one — is present.
 */
final class ShapeshifterAiFixture {

    static final DocRef MODEL = OpenAIModelDoc.buildDocRef().uuid("model-1").name("local-model").build();
    static final DocRef FRAGMENT = PipelineDoc.buildDocRef().uuid("fragment-1").name("syslog-v1").build();

    static final List<ScorerSetting> SCORERS = List.of(
            new ScorerSetting(ScorerType.COMPILE, 1.0, 1.0, true, null),
            new ScorerSetting(ScorerType.INPUT_COVERAGE, 2.0, 0.8, false, null),
            new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false, new BusinessRulesParameters(
                    List.of(new XPathAssertion("has a time", "EventTime/TimeCreated != ''")), true)));

    private ShapeshifterAiFixture() {
    }

    static ShapeshifterAiStoreImpl store() {
        final MockSecurityContext securityContext = new MockSecurityContext();
        return new ShapeshifterAiStoreImpl(
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, () -> null),
                securityContext,
                new ShapeshifterAiSerialiser(new Serialiser2FactoryImpl()));
    }

    static ShapeshifterAiDoc.Builder configured(final ShapeshifterAiDoc doc) {
        return doc.copy()
                .model(MODEL)
                .instructions("Prefer named fields.")
                .scorers(SCORERS);
    }
}
