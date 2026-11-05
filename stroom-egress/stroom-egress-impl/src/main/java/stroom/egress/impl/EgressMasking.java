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

/// Masks the part of a destination that can be a secret. A webhook URL carries its token in the last path segment, so
/// that segment is replaced. This protects what is shown and what is audited. It does not protect what is stored,
/// because matching needs the path.
public final class EgressMasking {

    private static final String MASK = "****";

    private EgressMasking() {
    }

    /// Masks a destination for display.
    ///
    /// @param kind        The kind of destination.
    /// @param destination The normalised destination.
    /// @return The destination with its last path segment masked, or unchanged for a file or an email address.
    public static String mask(final EgressKind kind, final String destination) {
        if (destination == null || kind == EgressKind.FILE || kind == EgressKind.EMAIL) {
            return destination;
        }
        final int schemeEnd = destination.indexOf("://");
        if (schemeEnd < 0) {
            return destination;
        }
        final int pathStart = destination.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return destination;
        }
        final String path = destination.substring(pathStart);
        final String trimmed = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        final int lastSlash = trimmed.lastIndexOf('/');
        // A bare host with the root path has nothing to mask.
        if (trimmed.length() <= 1 || lastSlash < 0) {
            return destination;
        }
        return destination.substring(0, pathStart) + trimmed.substring(0, lastSlash + 1) + MASK;
    }
}
