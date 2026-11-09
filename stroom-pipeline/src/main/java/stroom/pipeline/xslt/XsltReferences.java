/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.pipeline.xslt;

import stroom.dictionary.shared.DictionaryDoc;
import stroom.docref.DocRef;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.XsltReferenceKind;
import stroom.pipeline.shared.XsltReferenceReason;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything one XSLT body refers to.
 * <p>
 * A <b>parse failure</b> is held separately from the findings, and deliberately so. A malformed
 * stylesheet and a stylesheet naming a document that does not exist are different events with different
 * audiences: the first is already known to whoever is editing it and should not be reported, while the
 * second is the silent failure this parser exists to surface.
 *
 * @param references   The findings, in document order. Never null. Determinism matters: the same input
 *                     must always yield the same findings in the same order.
 * @param parseFailure A message describing why the body could not be read <b>in full</b>, or null if it
 *                     could. Findings may still be present alongside it: a body that is not well-formed
 *                     XML yields none, but one that exceeded the time budget yields whatever was reached
 *                     before the deadline, and those findings are no less true for being incomplete.
 */
public record XsltReferences(List<XsltReference> references, @Nullable String parseFailure) {

    private static final XsltReferences EMPTY = new XsltReferences(List.of(), null);
    private static final int MAX_STORED_LENGTH = 255;

    /**
     * @throws NullPointerException if {@code references} is null.
     */
    public XsltReferences {
        Objects.requireNonNull(references, "Null references supplied");
        references = List.copyOf(references);
    }

    /**
     * @return an empty result, as for a document with no body and nothing to find.
     */
    public static XsltReferences empty() {
        return EMPTY;
    }

    /**
     * @return a result recording that the body could not be read as XML.
     * @throws NullPointerException if {@code message} is null.
     */
    public static XsltReferences parseFailure(final String message) {
        return new XsltReferences(List.of(), Objects.requireNonNull(message, "Null message supplied"));
    }

    /**
     * @return a result holding the given findings.
     */
    public static XsltReferences of(final Collection<XsltReference> references) {
        return new XsltReferences(List.copyOf(references), null);
    }

    /**
     * @return true if the body could not be read in full, so the findings may be incomplete. Callers
     * should not report this to the author - they are editing a broken stylesheet and already know, and a
     * warning they see constantly is a warning they learn to dismiss.
     */
    public boolean hasParseFailure() {
        return parseFailure != null;
    }

    /// The documents this XSLT depends on, for recording as dependency edges.
    ///
    /// Three kinds of reference contribute:
    ///
    /// - **A resolved reference** contributes its target.
    /// - **An ambiguous name** contributes **every** candidate. A dependency edge means "may use" rather
    ///   than "will use" - references in never-matched templates are recorded too - so listing all
    ///   candidates keeps the edge set complete, where choosing one would invent a fact and choosing none
    ///   would lose a live edge. The candidates are recorded as ambiguous (see [#ambiguousTargets()]), so
    ///   they are reported as a problem to fix rather than as dependencies the XSLT follows.
    /// - **A name that matched nothing** contributes a dependency on a document that does not exist (see
    ///   [#notFoundTargets()]), so that it is reported as a missing dependency like any other.
    ///
    /// @return the distinct targets, in the order first encountered.
    public Set<DocRef> documentTargets() {
        final Set<DocRef> targets = new LinkedHashSet<>();
        for (final XsltReference reference : references) {
            if (reference.target() != null) {
                targets.add(reference.target());
            }
            targets.addAll(reference.candidates());
        }
        targets.addAll(notFoundTargets());
        return targets;
    }

    /// The targets that are recorded only as candidates of an ambiguous name.
    ///
    /// A candidate that another reference resolves to definitely, e.g. by UUID, is left out: the XSLT
    /// does depend on it, whatever else the ambiguous name may mean.
    ///
    /// @return the distinct ambiguous targets, in the order first encountered.
    public Set<DocRef> ambiguousTargets() {
        final Set<DocRef> ambiguous = new LinkedHashSet<>();
        final Set<DocRef> definite = new HashSet<>();
        for (final XsltReference reference : references) {
            if (reference.target() != null) {
                definite.add(reference.target());
            }
            ambiguous.addAll(reference.candidates());
        }
        ambiguous.removeAll(definite);
        return ambiguous;
    }

    /// Dependencies on the documents named by references that matched nothing.
    ///
    /// Only an import and a dictionary name a document, so only they contribute. Each becomes a [DocRef]
    /// whose UUID is what the runtime would look the reference up by, and whose name is the reference as
    /// written. No document has that UUID, so the dependency is reported as missing, and the explorer
    /// flags the XSLT. Were a document with that UUID to appear, the dependency would be satisfied with no
    /// further work; a document appearing with that **name** is noticed by the XSLT dependency refresher,
    /// which re-records the XSLT.
    ///
    /// A value too long to store, or blank, is left out. Neither can be a document's name or UUID.
    ///
    /// @return the distinct not-found targets, in the order first encountered.
    public Set<DocRef> notFoundTargets() {
        final Set<DocRef> targets = new LinkedHashSet<>();
        for (final XsltReference reference : withReason(XsltReferenceReason.NOT_FOUND)) {
            final String rawValue = reference.rawValue();
            final DocRef target = switch (reference.kind()) {
                // An import is looked up by name, then as a doc ref string or a bare UUID
                case IMPORT -> new DocRef(
                        XsltDoc.TYPE, CustomURIResolver.parseDocRef(rawValue).getUuid(), rawValue);
                // A dictionary is looked up by UUID, then by name, so both are the value as written
                case DICTIONARY -> new DocRef(DictionaryDoc.TYPE, rawValue, rawValue);
                default -> null;
            };
            if (target != null && isStorable(target.getUuid()) && isStorable(target.getName())) {
                targets.add(target);
            }
        }
        return targets;
    }

    /**
     * @return the findings that could not be resolved, in document order.
     */
    public List<XsltReference> unresolved() {
        return references.stream()
                .filter(reference -> !reference.isResolved())
                .toList();
    }

    /**
     * @return the findings with the given reason, in document order. Chiefly for
     * {@link XsltReferenceReason#NOT_FOUND}, which is the reason worth reporting to an author.
     */
    public List<XsltReference> withReason(final XsltReferenceReason reason) {
        Objects.requireNonNull(reason, "Null reason supplied");
        return references.stream()
                .filter(reference -> reason == reference.reason())
                .toList();
    }

    /**
     * @return the resolved values of the given kind, in document order, as written. Values are not
     * normalised or de-duplicated: case folding is the consumer's business, since Plan B lower-cases map
     * names and reference data does not.
     */
    public List<String> resolvedValues(final XsltReferenceKind kind) {
        Objects.requireNonNull(kind, "Null kind supplied");
        return references.stream()
                .filter(reference -> kind == reference.kind() && reference.isResolved())
                .map(XsltReference::rawValue)
                .toList();
    }

    // The doc_dependency columns are VARCHAR(255)
    private static boolean isStorable(final String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_STORED_LENGTH;
    }
}
