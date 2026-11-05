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

package stroom.egress.impl;

import stroom.egress.shared.EgressKind;

import java.util.Optional;
import java.util.Set;

/// Chooses the normaliser for a kind. A kind that is written as a URI also accepts a bare host and port where that
/// is how it is configured.
public final class EgressNormalisers {

    private static final DestinationNormaliser HTTP = new UriNormaliser(Set.of("http", "https"));
    private static final DestinationNormaliser OBJECT_STORE = new UriNormaliser(Set.of("s3", "http", "https"));
    private static final DestinationNormaliser MESSAGE_BUS = new UriNormaliser(Set.of("http", "https", "sqs"));
    private static final DestinationNormaliser FILE = new FileNormaliser();
    private static final DestinationNormaliser EMAIL = new EmailNormaliser();
    private static final DestinationNormaliser HOST_PORT = new HostPortNormaliser();

    private EgressNormalisers() {
    }

    /// Normalises a destination of the given kind.
    ///
    /// @param kind The kind.
    /// @param raw  The destination as written.
    /// @return The normalised destination, or empty if it must be refused.
    public static Optional<NormalisedDestination> normalise(final EgressKind kind, final String raw) {
        return switch (kind) {
            case HTTP, AI_MODEL, SEARCH_BACKEND -> HTTP.normalise(raw);
            case FILE -> FILE.normalise(raw);
            case EMAIL -> EMAIL.normalise(raw);
            case OBJECT_STORE -> OBJECT_STORE.normalise(raw);
            case MESSAGE_BUS -> raw != null && raw.contains("://")
                    ? MESSAGE_BUS.normalise(raw)
                    : HOST_PORT.normalise(raw);
        };
    }
}
