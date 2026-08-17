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
import stroom.pipeline.shared.XsltReferenceCertainty;
import stroom.pipeline.shared.XsltReferenceDirection;
import stroom.pipeline.shared.XsltReferenceKind;
import stroom.pipeline.shared.XsltReferenceReason;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * One thing an XSLT refers to, as found by {@link XsltReferenceParser}.
 * <p>
 * A finding is either <b>resolved</b> ({@code reason == null}) or <b>unresolved</b>. Note that
 * "resolved" does not imply a document: a map name is fully resolved when its literal value is known,
 * yet has no target, because a map name does not identify a document on its own.
 *
 * @param kind        What sort of reference this is. Never null.
 * @param rawValue    The value as written, before any normalisation. Never null. For an unresolved
 *                    reference this is the expression or name that could not be resolved, which is what
 *                    a human needs in order to find it in the source.
 * @param target      The document referred to, or null where there is none - either because the kind
 *                    has no document target, or because the reference is unresolved.
 * @param candidates  Where {@code reason} is {@link XsltReferenceReason#AMBIGUOUS}, every document the
 *                    name matched, so a report can name the collision and a consumer can record an edge
 *                    to each. Never null; empty for every other reason.
 * @param reason      Why this reference is unresolved, or null if it is resolved.
 * @param certainty   Whether the value was written literally or folded from literals. Never null.
 * @param direction   For {@link XsltReferenceKind#HTTP}, which way data flows; null otherwise.
 * @param lineNumber  The line in the XSLT body the reference was found on, or -1 if unknown. Present so
 *                    a report can point at the source rather than merely describing it.
 */
public record XsltReference(
        XsltReferenceKind kind,
        String rawValue,
        @Nullable DocRef target,
        List<DocRef> candidates,
        @Nullable XsltReferenceReason reason,
        XsltReferenceCertainty certainty,
        @Nullable XsltReferenceDirection direction,
        int lineNumber) {

    /**
     * @throws NullPointerException     if {@code kind}, {@code rawValue}, {@code candidates} or
     *                                  {@code certainty} is null.
     * @throws IllegalArgumentException if the finding is internally inconsistent, i.e. it carries both a
     *                                  target and a reason, or carries candidates without being
     *                                  {@link XsltReferenceReason#AMBIGUOUS}.
     */
    public XsltReference {
        Objects.requireNonNull(kind, "Null kind supplied");
        Objects.requireNonNull(rawValue, "Null rawValue supplied");
        Objects.requireNonNull(candidates, "Null candidates supplied");
        Objects.requireNonNull(certainty, "Null certainty supplied");

        if (target != null && reason != null) {
            throw new IllegalArgumentException(
                    "A reference cannot be both resolved to " + target + " and unresolved because " + reason);
        }
        if (!candidates.isEmpty() && reason != XsltReferenceReason.AMBIGUOUS) {
            throw new IllegalArgumentException(
                    "Candidates are only meaningful for AMBIGUOUS, not " + reason);
        }
        candidates = List.copyOf(candidates);
    }

    /**
     * @return true if the value was determined. A resolved reference of a kind that has no document
     * target, such as a map name, still returns true.
     */
    public boolean isResolved() {
        return reason == null;
    }

    /**
     * A reference resolved to a single document.
     */
    static XsltReference document(final XsltReferenceKind kind,
                                  final String rawValue,
                                  final DocRef target,
                                  final XsltReferenceCertainty certainty,
                                  final int lineNumber) {
        Objects.requireNonNull(target, "Null target supplied");
        return new XsltReference(
                kind, rawValue, target, List.of(), null, certainty, null, lineNumber);
    }

    /**
     * A name that matched several documents. Deliberately unresolved, but carrying the candidates so the
     * collision can be reported and acted on.
     */
    static XsltReference ambiguous(final XsltReferenceKind kind,
                                   final String rawValue,
                                   final List<DocRef> candidates,
                                   final XsltReferenceCertainty certainty,
                                   final int lineNumber) {
        if (Objects.requireNonNull(candidates, "Null candidates supplied").size() < 2) {
            throw new IllegalArgumentException("AMBIGUOUS requires at least two candidates");
        }
        return new XsltReference(
                kind,
                rawValue,
                null,
                candidates,
                XsltReferenceReason.AMBIGUOUS,
                certainty,
                null,
                lineNumber);
    }

    /**
     * A reference that could not be resolved, for a reason other than ambiguity.
     */
    static XsltReference unresolved(final XsltReferenceKind kind,
                                    final String rawValue,
                                    final XsltReferenceReason reason,
                                    final XsltReferenceCertainty certainty,
                                    final int lineNumber) {
        Objects.requireNonNull(reason, "Null reason supplied");
        if (reason == XsltReferenceReason.AMBIGUOUS) {
            throw new IllegalArgumentException("Use ambiguous() so the candidates are carried");
        }
        return new XsltReference(
                kind, rawValue, null, List.of(), reason, certainty, null, lineNumber);
    }

    /**
     * A map name, read or written. Resolved, but with no document target - see
     * {@link XsltReferenceKind#REF_MAP_READ}.
     */
    static XsltReference mapName(final XsltReferenceKind kind,
                                 final String rawValue,
                                 final XsltReferenceCertainty certainty,
                                 final int lineNumber) {
        if (kind != XsltReferenceKind.REF_MAP_READ && kind != XsltReferenceKind.REF_MAP_WRITE) {
            throw new IllegalArgumentException("Not a map kind: " + kind);
        }
        return new XsltReference(
                kind, rawValue, null, List.of(), null, certainty, null, lineNumber);
    }

    /**
     * An external endpoint. Resolved, but with no document target, being outside Stroom.
     */
    static XsltReference endpoint(final String rawValue,
                                  final XsltReferenceDirection direction,
                                  final XsltReferenceCertainty certainty,
                                  final int lineNumber) {
        Objects.requireNonNull(direction, "Null direction supplied");
        return new XsltReference(
                XsltReferenceKind.HTTP, rawValue, null, List.of(), null, certainty, direction, lineNumber);
    }
}
