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

import stroom.pipeline.shared.XsltReferenceCheckResult;
import stroom.pipeline.shared.XsltReferenceInfo;
import stroom.pipeline.shared.XsltReferenceReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts the parser's findings into the shape sent to a client.
 * <p>
 * A separate type from the findings themselves because the two have different obligations. The parser's
 * model is free to use records and null annotations; the wire model must be GWT-compilable and stable for
 * clients, and the vocabulary it exposes - the kind, reason and direction enums - lives in shared code for
 * both sides to agree on.
 */
final class XsltReferenceInfoMapper {

    private XsltReferenceInfoMapper() {
        // Static utility.
    }

    /// The findings as the user sees them, marked where the runtime would find a name ambiguous because of
    /// documents the user cannot view.
    ///
    /// Each finding is reported as `asUser` has it, so nothing names or counts a document the user cannot
    /// see. The processing user's findings contribute one fact only: that a name matches more documents than
    /// the user can see, and so is ambiguous at runtime (an import fails to compile, a dictionary uses the
    /// match with the lowest UUID). Such a finding is reported as ambiguous with
    /// [XsltReferenceInfo#isHiddenMatches()] set, keeping any match the user can see.
    ///
    /// The two parses walk the same text, so they find the same references in the same order and differ
    /// only in what names resolve to. Where they do not line up, e.g. one ran out of time, nothing is marked.
    ///
    /// @param asUser           What the parser found, resolving names as the user. Must not be null.
    /// @param asProcessingUser What it found resolving names as the processing user. Must not be null.
    /// @return the user's findings, in the same order, as a client-facing result.
    static XsltReferenceCheckResult toResult(final XsltReferences asUser, final XsltReferences asProcessingUser) {
        Objects.requireNonNull(asUser, "Null asUser supplied");
        Objects.requireNonNull(asProcessingUser, "Null asProcessingUser supplied");
        final List<XsltReference> userReferences = asUser.references();
        final List<XsltReference> processingReferences = asProcessingUser.references();
        final boolean aligned = userReferences.size() == processingReferences.size();
        final List<XsltReferenceInfo> infos = new ArrayList<>(userReferences.size());
        for (int i = 0; i < userReferences.size(); i++) {
            final XsltReference reference = userReferences.get(i);
            final boolean hiddenMatches = aligned && hasHiddenMatches(reference, processingReferences.get(i));
            infos.add(toInfo(reference, hiddenMatches));
        }
        return new XsltReferenceCheckResult(infos, asUser.parseFailure());
    }

    // True if the processing user finds the name ambiguous with more matches than the user can see
    private static boolean hasHiddenMatches(final XsltReference asUser, final XsltReference asProcessingUser) {
        final boolean sameReference = asUser.kind() == asProcessingUser.kind()
                                      && asUser.rawValue().equals(asProcessingUser.rawValue())
                                      && asUser.lineNumber() == asProcessingUser.lineNumber();
        if (!sameReference || asProcessingUser.reason() != XsltReferenceReason.AMBIGUOUS) {
            return false;
        }
        final int visibleMatches = asUser.target() != null
                ? 1
                : asUser.candidates().size();
        return asProcessingUser.candidates().size() > visibleMatches;
    }

    private static XsltReferenceInfo toInfo(final XsltReference reference, final boolean hiddenMatches) {
        // A name the user sees as resolved, or not found, is ambiguous once hidden matches are counted
        final XsltReferenceReason reason = hiddenMatches
                ? XsltReferenceReason.AMBIGUOUS
                : reference.reason();
        return new XsltReferenceInfo(
                reference.kind(),
                reference.rawValue(),
                reference.target(),
                reference.candidates(),
                reason,
                reference.direction(),
                reference.lineNumber(),
                hiddenMatches);
    }
}
