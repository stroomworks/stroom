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

import org.jspecify.annotations.Nullable;

/**
 * Reads an XSLT body and reports what it refers to.
 * <p>
 * Everything an XSLT refers to lives as a string in its body rather than as a {@code DocRef} field -
 * imports and includes, {@code stroom:dictionary} names, lookup map names, {@code stroom:http-call}
 * URLs - so none of it is visible to the dependency machinery without reading the stylesheet.
 * <p>
 * Three properties define the contract, and all three matter more than completeness:
 * <ul>
 *     <li><b>It never throws.</b> A malformed or half-edited stylesheet must still save.</li>
 *     <li><b>It never guesses.</b> A reference it cannot determine is reported unresolved, with a reason.
 *     A false negative costs a missing edge; a false positive is a lie about the configuration.</li>
 *     <li><b>It reads one document in isolation.</b> Imports are recorded but not followed, so a broken
 *     import cannot blind the parser to the rest of the document.</li>
 * </ul>
 */
public interface XsltReferenceParser {

    /**
     * Parse an XSLT body.
     * <p>
     * This method does not throw. Anything it cannot handle comes back as an unresolved finding or, where
     * the body is not readable as XML at all, as {@link XsltReferences#parseFailure()}.
     *
     * @param xsltData The XSLT body, or null for a document that has none yet. A null or blank body is
     *                 not a failure - it is an empty stylesheet, and yields an empty result.
     * @return what the body refers to. Never null.
     */
    XsltReferences parse(@Nullable String xsltData);
}
