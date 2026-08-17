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

    /**
     * @param references What the parser found. Must not be null.
     * @return the same findings, in the same order, as a client-facing result.
     */
    static XsltReferenceCheckResult toResult(final XsltReferences references) {
        Objects.requireNonNull(references, "Null references supplied");
        final List<XsltReferenceInfo> infos = references.references().stream()
                .map(XsltReferenceInfoMapper::toInfo)
                .toList();
        return new XsltReferenceCheckResult(infos, references.parseFailure());
    }

    private static XsltReferenceInfo toInfo(final XsltReference reference) {
        return new XsltReferenceInfo(
                reference.kind(),
                reference.rawValue(),
                reference.target(),
                reference.candidates(),
                reference.reason(),
                reference.direction(),
                reference.lineNumber());
    }
}
