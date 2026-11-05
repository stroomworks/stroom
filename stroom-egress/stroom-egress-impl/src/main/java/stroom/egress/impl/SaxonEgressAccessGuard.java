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

import stroom.egress.api.EgressDecision;
import stroom.egress.api.EgressGuard;
import stroom.egress.api.EgressRequest;
import stroom.egress.shared.EgressKind;
import stroom.util.xml.saxon.SaxonAccessGuard;
import stroom.util.xml.saxon.SaxonRoute;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/// Connects the Saxon hooks to the egress policy. `doc()` and `document()` are checked by rule, as HTTP for an `http`
/// or `https` URI and as FILE for a `file` URI. `unparsed-text`, `collection` and `xsl:result-document` are denied
/// outright, in every mode, and the attempt is recorded.
@Singleton
public class SaxonEgressAccessGuard implements SaxonAccessGuard {

    private final EgressGuard guard;

    /// Creates the adapter.
    ///
    /// @param guard The guard.
    @Inject
    public SaxonEgressAccessGuard(final EgressGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean permit(final SaxonRoute route, final String uri) {
        final EgressRequest request = toRequest(route, uri);
        final EgressDecision decision = route.isDeniedOutright()
                ? guard.denyOutright(request)
                : guard.check(request);
        return decision.isAllowed();
    }

    private static EgressRequest toRequest(final SaxonRoute route, final String uri) {
        final String sink = sinkName(route);
        final String scheme = schemeOf(uri);
        if ("file".equals(scheme)) {
            return new EgressRequest(EgressKind.FILE, filePath(uri), sink);
        }
        if (scheme == null && uri != null && uri.startsWith("/")) {
            return new EgressRequest(EgressKind.FILE, uri, sink);
        }
        return new EgressRequest(EgressKind.HTTP, uri, sink);
    }

    private static String sinkName(final SaxonRoute route) {
        return switch (route) {
            case DOCUMENT -> "doc()";
            case UNPARSED_TEXT -> "unparsed-text()";
            case COLLECTION -> "collection()";
            case RESULT_DOCUMENT -> "xsl:result-document";
        };
    }

    private static String schemeOf(final String uri) {
        if (uri == null) {
            return null;
        }
        final int colon = uri.indexOf(':');
        if (colon < 2) {
            return null;
        }
        return uri.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    // A file URI that cannot be turned into a path is passed on as it is and will fail to normalise.
    private static String filePath(final String uri) {
        try {
            final Path path = Paths.get(new URI(uri));
            return path.toString();
        } catch (final Exception e) {
            return uri;
        }
    }
}
