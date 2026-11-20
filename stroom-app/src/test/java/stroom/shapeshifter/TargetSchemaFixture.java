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

package stroom.shapeshifter;

import stroom.docref.DocRef;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.shapeshifter.ai.learning.TargetSchema;
import stroom.xmlschema.shared.XmlSchemaDoc;

/// The schema version a scenario's canned answers actually write (task 7, decision D1).
///
/// A new document starts on the **latest** event-logging installed, and a test node installs all seven
/// versions, so a document made here targets 4.1.0. A scenario's answers are not a model's: they are
/// fixture files, and every one of them writes `Version="3.0.0"` — which event-logging 4.1.0 refuses
/// outright, its `VersionSimpleType` allowing the single value `"4.1.0"`.
///
/// So a scenario says which version its own fixtures write, rather than relying on being judged against
/// a whole group. A scenario about a live model would say nothing and take the default.
public final class TargetSchemaFixture {

    /// The system id of the version the module's fixtures and demo content are written for.
    public static final String FIXTURES_WRITE = "file://event-logging-v3.0.0.xsd";

    private TargetSchemaFixture() {
    }

    /// @param schemaStore A node's schema store.
    /// @return The `XMLSchema` document for the version the fixtures write.
    public static DocRef eventLogging300(final XmlSchemaStore schemaStore) {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(TargetSchema.EVENT_LOGGING);
        criteria.setSystemId(FIXTURES_WRITE);
        return schemaStore.find(criteria).getValues().stream()
                .findFirst()
                .map(XmlSchemaDoc::asDocRef)
                .orElseThrow(() -> new IllegalStateException(
                        "This node has no event-logging schema at " + FIXTURES_WRITE
                        + ", which every scenario fixture writes"));
    }
}
