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

package stroom.pipeline.xslt;

import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.XsltReferenceCheckResult;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// Checks what an XSLT refers to, for its editor's References tab.
///
/// Each reference is resolved twice. The report itself is resolved with the caller's permissions, so a
/// document they cannot view is never named: it is reported as not found, and a match they cannot see is
/// not listed. Resolving as the processing user as well, as the runtime and the recorded dependencies do,
/// adds one fact and no more: that a name also matches documents the caller cannot view, and so is
/// ambiguous at runtime even though the caller sees one match or none. The report says so without saying
/// how many such documents there are (see [XsltReferenceInfoMapper]). That much is already disclosed by the
/// runtime's error ("Found 2 XSLTs named ...") and by the explorer, which flags the XSLT;
/// `XsltStoreImpl.getDependencyRemapFunction` explains why that is acceptable.
///
/// A document the caller cannot view that a name resolves to unambiguously is still reported as not
/// found. Saying otherwise would reveal that a document by that name exists.
class XsltReferenceCheckService {

    private final Provider<XsltReferenceParser> referenceParserProvider;
    private final SecurityContext securityContext;

    @Inject
    XsltReferenceCheckService(final Provider<XsltReferenceParser> referenceParserProvider,
                              final SecurityContext securityContext) {
        this.referenceParserProvider = referenceParserProvider;
        this.securityContext = securityContext;
    }

    /// Checks an XSLT's references.
    ///
    /// The caller's own copy is checked where they supplied one and may change the XSLT, so an author
    /// checks what is in front of them rather than what was last saved. Anyone else is checked against
    /// the saved copy: otherwise anyone able to view one XSLT could send any names they liked and learn,
    /// name by name, whether documents they cannot view share them, across the whole store, rather than
    /// only what the stylesheet they may read already names.
    ///
    /// @param doc          The XSLT, read with the caller's permissions.
    /// @param suppliedData The text in the caller's editor, or null.
    /// @return what it refers to, and the problems among those references.
    XsltReferenceCheckResult check(final XsltDoc doc, final String suppliedData) {
        final String data = suppliedData != null
                            && securityContext.hasDocumentPermission(doc.asDocRef(), DocumentPermission.EDIT)
                ? suppliedData
                : doc.getData();

        final XsltReferenceParser parser = referenceParserProvider.get();
        final XsltReferences asUser = parser.parse(data);
        final XsltReferences asProcessingUser = securityContext.asProcessingUserResult(() -> parser.parse(data));
        return XsltReferenceInfoMapper.toResult(asUser, asProcessingUser);
    }
}
