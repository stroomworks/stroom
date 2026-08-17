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

import stroom.docref.DocRef;
import stroom.pipeline.shared.XsltReferenceKind;
import stroom.pipeline.shared.XsltReferenceReason;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
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

    /**
     * The documents this XSLT depends on, for recording as dependency edges.
     * <p>
     * An ambiguous name contributes <b>every</b> candidate. A dependency edge means "may use" rather
     * than "will use" - references in never-matched templates are recorded too - so listing all
     * candidates keeps the edge set complete, where choosing one would invent a fact and choosing none
     * would lose a live edge.
     *
     * @return the distinct targets, in the order first encountered.
     */
    public Set<DocRef> documentTargets() {
        final Set<DocRef> targets = new LinkedHashSet<>();
        for (final XsltReference reference : references) {
            if (reference.target() != null) {
                targets.add(reference.target());
            }
            targets.addAll(reference.candidates());
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
}
