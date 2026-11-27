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

/// Looks up the [TargetSchema] a Learning Flow's translations must produce. In a node it reads Stroom's
/// `XMLSchema` documents; the scenario test harness, which has no document store, holds the schemas it was
/// given.
public interface TargetSchemas {

    /// Reads the schema a document names.
    ///
    /// A schema that has been deleted, or that this node cannot read, gives empty rather than throwing,
    /// because what to do about it is for the caller to decide: the readiness check reports it, and a run
    /// refuses to start before asking anything.
    ///
    /// @param docRef The `XMLSchema` document, or null.
    /// @return The resolved schema, or empty where the reference is null, deleted or unreadable.
    Optional<TargetSchema> resolve(DocRef docRef);

    /// Returns the latest version of event-logging installed, which a new document starts with. Latest is
    /// judged by the version its XSD declares, not by its name, and a deprecated version is never chosen.
    /// The choice is saved on the document, so installing a newer version later does not change what an
    /// existing document produces.
    ///
    /// @return The schema, or empty where no event-logging schema is installed, in which case a new
    ///         document has no target until someone picks one, and says so.
    Optional<TargetSchema> latestEventLogging();

    /// Returns event-logging v3.0.0, which a document saved before it could name a target schema was learned
    /// against, so that the store can read such a document with the target it always had.
    ///
    /// A lookup with no schemas, such as [#none()] or one made for a test, finds none.
    ///
    /// @return The schema, or empty where v3.0.0 is not installed.
    default Optional<TargetSchema> eventLoggingV3() {
        return Optional.empty();
    }

    /// Returns the schema a document's translations must produce.
    ///
    /// @param doc The document.
    /// @return The resolved schema, or empty where the document names none or names one that is gone.
    default Optional<TargetSchema> of(final LearningFlowDoc doc) {
        return doc == null
                ? Optional.empty()
                : resolve(doc.getTargetSchema());
    }

    /// Returns a lookup that finds no schemas at all, for tests that do not care about the target.
    ///
    /// @return The empty lookup.
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
