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
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.memory.MemoryPersistence;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.XPathAssertion;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.mock.MockSecurityContext;
import stroom.security.shared.AppPermission;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// Shared set-up for the Learning Flow document store tests.
///
/// It builds a store over the real `StoreImpl` with in-memory persistence, and configures a document with a
/// model, instructions and a full set of checks. `FRAGMENT` is a pipeline reference the document does not
/// hold, so tests can show the store does not treat it as a dependency.
final class LearningFlowFixture {

    static final DocRef MODEL = OpenAIModelDoc.buildDocRef().uuid("model-1").name("local-model").build();
    static final DocRef FRAGMENT = PipelineDoc.buildDocRef().uuid("fragment-1").name("syslog-v1").build();

    static final List<CheckSetting> CHECKS = List.of(
            new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
            new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
            CheckSetting.of(Check.COMPLETENESS, CheckState.GATE),
            CheckSetting.of(Check.YIELD, CheckState.OFF),
            CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
            CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
            CheckSetting.of(Check.QUALITY, CheckState.OFF),
            new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0, new BusinessRulesParameters(
                    List.of(new XPathAssertion("has a time", "EventTime/TimeCreated != ''")), true)),
            CheckSetting.of(Check.TIMES, CheckState.ADVICE),
            CheckSetting.of(Check.LITERALS, CheckState.ADVICE),
            CheckSetting.of(Check.PERMITTED, CheckState.GATE),
            CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
            CheckSetting.of(Check.FIDELITY, CheckState.GATE),
            CheckSetting.of(Check.REGRESSION, CheckState.GATE));

    private LearningFlowFixture() {
    }

    static LearningFlowStoreImpl store() {
        return store(TargetSchemas.none());
    }

    /// Creates a store over its own in-memory persistence that reads target schemas from the source given, so
    /// a test can control which target schemas a new document starts with.
    ///
    /// @param targetSchemas The source of the target schemas offered to new documents.
    /// @return The store.
    static LearningFlowStoreImpl store(final TargetSchemas targetSchemas) {
        final MockSecurityContext securityContext = new MockSecurityContext();
        return new LearningFlowStoreImpl(
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, () -> null),
                securityContext,
                new LearningFlowSerialiser(new Serialiser2FactoryImpl()),
                targetSchemas);
    }

    /// A store over its own in-memory persistence whose user can have an application permission taken away
    /// part way through, so that one test can write a document as someone who holds a permission and then
    /// edit that same document as someone who does not.
    ///
    /// @return The store and the set of permissions to withhold from it.
    static Restricted restricted() {
        final Set<AppPermission> withheld = EnumSet.noneOf(AppPermission.class);
        final MockSecurityContext securityContext = new WithheldPermissions(withheld);
        return new Restricted(new LearningFlowStoreImpl(
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, () -> null),
                securityContext,
                new LearningFlowSerialiser(new Serialiser2FactoryImpl()),
                TargetSchemas.none()),
                withheld);
    }

    static LearningFlowDoc.Builder configured(final LearningFlowDoc doc) {
        return doc.copy()
                .model(MODEL)
                .instructions("Prefer named fields.")
                .checks(CHECKS);
    }


    // --------------------------------------------------------------------------------


    /// A store and the permissions its user does not hold. The set is the one the store's security context
    /// reads, so adding to it takes the permission away from the next call and removing gives it back.
    ///
    /// @param store    The store.
    /// @param withheld The permissions its user does not hold.
    record Restricted(LearningFlowStoreImpl store, Set<AppPermission> withheld) {

        /// Takes a permission away from the store's user.
        ///
        /// @param permission The permission to withhold.
        void withhold(final AppPermission permission) {
            withheld.add(permission);
        }

        /// Gives a permission back to the store's user.
        ///
        /// @param permission The permission to grant.
        void grant(final AppPermission permission) {
            withheld.remove(permission);
        }
    }


    // --------------------------------------------------------------------------------


    /// A security context that holds every application permission the mock holds, less those a test has
    /// withheld.
    private static final class WithheldPermissions extends MockSecurityContext {

        private final Set<AppPermission> withheld;

        private WithheldPermissions(final Set<AppPermission> withheld) {
            this.withheld = withheld;
        }

        @Override
        public boolean hasAppPermission(final AppPermission permission) {
            return !withheld.contains(permission);
        }
    }
}
