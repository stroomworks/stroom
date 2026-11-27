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

package stroom.learningflow;

import stroom.docref.DocRef;
import stroom.learningflow.learning.TargetSchema;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.xmlschema.shared.XmlSchemaDoc;

/// Finds the event-logging schema version that the scenario tests' canned answers are written for.
///
/// A new Learning Flow document targets the latest installed event-logging schema, and a test node
/// installs all seven versions, so a document made in a test targets 4.1.0. The canned answers are fixture
/// files rather than a model's output, and every one writes `Version="3.0.0"`, which 4.1.0 rejects because
/// its `VersionSimpleType` allows only `"4.1.0"`.
///
/// A scenario therefore sets its document's target schema to the version its fixtures write. A test that
/// uses a live model would not need to and would keep the default.
public final class TargetSchemaFixture {

    /// The system id of the version the module's fixtures and demo content are written for.
    public static final String FIXTURES_WRITE = "file://event-logging-v3.0.0.xsd";

    private TargetSchemaFixture() {
    }

    /// Looks up the event-logging 3.0.0 schema document in a test node.
    ///
    /// @param schemaStore The test node's schema store.
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
