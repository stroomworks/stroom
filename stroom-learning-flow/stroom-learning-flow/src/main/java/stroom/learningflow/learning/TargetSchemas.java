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

package stroom.learningflow.learning;

import stroom.docref.DocRef;
import stroom.learningflow.shared.LearningFlowDoc;

import java.util.Optional;

/// Where the one schema a document's translations must write is read from (task 7, decision D1
/// option A). A node reads Stroom's `XMLSchema` documents; the Tier 1 scenario harness, which has no
/// document store under it, holds the schemas it was given.
public interface TargetSchemas {

    /// Reads the schema a document names.
    ///
    /// A schema that has been deleted, or that this node cannot read, gives empty rather than throwing:
    /// what to do about it belongs to the caller — the readiness check says so in words, and a run
    /// refuses before asking anything.
    ///
    /// @param docRef The `XMLSchema` document, or null.
    /// @return It resolved, or empty where it is null, gone or unreadable.
    Optional<TargetSchema> resolve(DocRef docRef);

    /// The latest version of event-logging installed, which a new document starts with (decision D1):
    /// latest by the version its XSD declares, not by its name, and never a deprecated one. It is saved
    /// on the document, so installing a newer version later does not change what a document that
    /// already exists writes.
    ///
    /// @return It, or empty where no event-logging schema is installed — in which case a new document
    ///         has no target until someone picks one, and says so.
    Optional<TargetSchema> latestEventLogging();

    /// The schema a document's translations must write.
    ///
    /// @param doc The document.
    /// @return It resolved, or empty where the document names none or names one that is gone.
    default Optional<TargetSchema> of(final LearningFlowDoc doc) {
        return doc == null
                ? Optional.empty()
                : resolve(doc.getTargetSchema());
    }

    /// A node with no schemas at all: what a test that does not care about the target uses.
    ///
    /// @return It.
    static TargetSchemas none() {
        return new TargetSchemas() {
            @Override
            public Optional<TargetSchema> resolve(final DocRef docRef) {
                return Optional.empty();
            }

            @Override
            public Optional<TargetSchema> latestEventLogging() {
                return Optional.empty();
            }
        };
    }
}
