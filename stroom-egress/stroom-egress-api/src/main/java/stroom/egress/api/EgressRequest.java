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

package stroom.egress.api;

import stroom.egress.shared.EgressKind;

import java.util.Objects;

/// A request to send data to a destination.
///
/// @param kind        The kind of destination.
/// @param destination The destination exactly as the caller supplied it.
/// @param sink        The name of the sink making the request, for example `http-call` or `FileAppender`.
public record EgressRequest(EgressKind kind, String destination, String sink) {

    public EgressRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sink, "sink");
    }
}
