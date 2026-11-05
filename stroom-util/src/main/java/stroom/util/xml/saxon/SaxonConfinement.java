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

package stroom.util.xml.saxon;

import net.sf.saxon.Configuration;
import net.sf.saxon.lib.CollectionFinder;
import net.sf.saxon.lib.FeatureKeys;
import net.sf.saxon.lib.OutputURIResolver;
import net.sf.saxon.lib.UnparsedTextURIResolver;
import net.sf.saxon.trans.XPathException;

import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;

/// Installs the four Saxon hooks, together, on one Saxon configuration, so that every site uses the same code and no
/// site is missed.
///
/// Saxon gives each family of routes its own hook and none covers another's: `setURIResolver` covers `doc`,
/// `document`, `doc-available` and `xsl:source-document`; `setUnparsedTextURIResolver` covers `unparsed-text*` and
/// `json-doc`; `setCollectionFinder` covers `collection` and `uri-collection`; `setOutputURIResolver` covers
/// `xsl:result-document`. The compile-time resolver on the `XsltCompiler`, which governs `xsl:import` and
/// `xsl:include`, is separate and is not touched here.
///
/// Reflexive external function calls are also switched off. That does not affect Stroom's extension functions, which
/// are registered as integrated functions.
///
/// Every hook asks a [SaxonAccessGuard]. The guard is chosen at the site, or is the one installed at start-up with
/// [#install(SaxonAccessGuard)]. Until one is installed, `document()` and friends are allowed and the three routes that
/// are denied outright are refused, so a site that is confined before the egress policy exists is never less safe than
/// the rule.
public final class SaxonConfinement {

    private static final String REFUSED = "The stylesheet may not read external resources (or write any), and was refused: ";

    private static final SaxonAccessGuard DEFAULT_GUARD = (route, uri) -> !route.isDeniedOutright();
    private static final SaxonAccessGuard REFUSE_ALL = (route, uri) -> false;
    // Asks whichever guard is installed at the time of each attempt, so a site that is confined before start-up has
    // finished (a static field, for example) still uses the egress policy once it is installed.
    private static final SaxonAccessGuard INSTALLED = (route, uri) -> SaxonConfinement.installed.permit(route, uri);

    private static volatile SaxonAccessGuard installed = DEFAULT_GUARD;

    private SaxonConfinement() {
    }

    /// Installs the guard used by every site that does not name one. Called once at start-up.
    ///
    /// @param guard The guard.
    public static void install(final SaxonAccessGuard guard) {
        installed = guard == null
                ? DEFAULT_GUARD
                : guard;
    }

    /// A guard that refuses every route, for sites that must never reach outside their input.
    ///
    /// @return The guard.
    public static SaxonAccessGuard refuseAll() {
        return REFUSE_ALL;
    }

    /// Confines a configuration using the installed guard. Saxon recovers from a refused `document()` with a
    /// warning and an empty result, and that is left as it is, so existing translations behave as they did.
    ///
    /// @param configuration The configuration to confine.
    public static void confine(final Configuration configuration) {
        confine(configuration, INSTALLED, false);
    }

    /// Confines a configuration.
    ///
    /// @param configuration The configuration to confine.
    /// @param guard         The guard every hook asks.
    /// @param strict        True to turn off Saxon's recovery, so a refused `document()` is an error and not an empty
    ///                      sequence. For content that is not trusted.
    public static void confine(final Configuration configuration, final SaxonAccessGuard guard, final boolean strict) {
        final URIResolver previousResolver = configuration.getURIResolver();
        configuration.setURIResolver(new GuardedUriResolver(guard, previousResolver));

        final UnparsedTextURIResolver previousUnparsed = configuration.getUnparsedTextURIResolver();
        configuration.setUnparsedTextURIResolver((uri, encoding, config) -> {
            require(guard, SaxonRoute.UNPARSED_TEXT, uri.toString());
            final Reader reader = previousUnparsed.resolve(uri, encoding, config);
            return reader;
        });

        final CollectionFinder previousFinder = configuration.getCollectionFinder();
        configuration.setCollectionFinder((context, uri) -> {
            require(guard, SaxonRoute.COLLECTION, uri);
            return previousFinder.findCollection(context, uri);
        });

        configuration.setOutputURIResolver(new GuardedOutputUriResolver(guard));
        configuration.setConfigurationProperty(FeatureKeys.ALLOW_EXTERNAL_FUNCTIONS, false);
        if (strict) {
            configuration.setRecoveryPolicy(Configuration.DO_NOT_RECOVER);
        }
    }

    private static void require(final SaxonAccessGuard guard,
                                final SaxonRoute route,
                                final String uri) throws XPathException {
        if (!guard.permit(route, uri)) {
            throw new XPathException(REFUSED + uri);
        }
    }

    private static String absolute(final String href, final String base) {
        try {
            final URI uri = new URI(href);
            if (uri.isAbsolute() || base == null || base.isEmpty()) {
                return uri.toString();
            }
            return new URI(base).resolve(uri).toString();
        } catch (final URISyntaxException | IllegalArgumentException e) {
            return href;
        }
    }


    // --------------------------------------------------------------------------------


    private static final class GuardedUriResolver implements URIResolver {

        private final SaxonAccessGuard guard;
        private final URIResolver previous;

        private GuardedUriResolver(final SaxonAccessGuard guard, final URIResolver previous) {
            this.guard = guard;
            this.previous = previous;
        }

        @Override
        public Source resolve(final String href, final String base) throws TransformerException {
            final String uri = absolute(href, base);
            if (!guard.permit(SaxonRoute.DOCUMENT, uri)) {
                throw new TransformerException(REFUSED + uri);
            }
            return previous == null
                    ? null
                    : previous.resolve(href, base);
        }
    }


    // --------------------------------------------------------------------------------


    // Writing is never permitted: the guard is asked so that the attempt is recorded, and the route then refuses.
    private static final class GuardedOutputUriResolver implements OutputURIResolver {

        private final SaxonAccessGuard guard;

        private GuardedOutputUriResolver(final SaxonAccessGuard guard) {
            this.guard = guard;
        }

        @Override
        public Result resolve(final String href, final String base) throws XPathException {
            final String uri = absolute(href, base);
            guard.permit(SaxonRoute.RESULT_DOCUMENT, uri);
            throw new XPathException(REFUSED + uri);
        }

        @Override
        public void close(final Result result) throws XPathException {
            throw new XPathException(REFUSED + "result-document");
        }

        @Override
        public OutputURIResolver newInstance() {
            return new GuardedOutputUriResolver(guard);
        }
    }
}
