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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;

import java.util.Optional;

/// The event-logging schemas installed, as a Learning Flow's target schema is chosen from them (D1): the latest
/// version, which a new Learning Flow is given, and v3.0.0, which a Learning Flow saved before it could name a
/// target was learned against.
public interface EventLoggingSchemas {

    /// No event-logging schema installed: for a store with no schemas to read.
    EventLoggingSchemas NONE = new EventLoggingSchemas() {
        @Override
        public Optional<DocRef> latest() {
            return Optional.empty();
        }

        @Override
        public Optional<DocRef> v3() {
            return Optional.empty();
        }
    };

    /// @return The latest event-logging version installed, by the version its schema declares, or empty where
    ///         none is.
    Optional<DocRef> latest();

    /// @return Event-logging v3.0.0, or empty where it isn't installed.
    Optional<DocRef> v3();
}
