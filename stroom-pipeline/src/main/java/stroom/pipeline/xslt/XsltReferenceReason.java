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

/**
 * Why a reference could not be resolved to a value, or to a document.
 * <p>
 * A reason is not a fault. Most of these describe an XSLT that is working exactly as intended but
 * whose target cannot be known without running it. Only {@link #NOT_FOUND} and {@link #AMBIGUOUS}
 * describe something an author would want to act on.
 */
public enum XsltReferenceReason {

    /**
     * The value depends on the input document, e.g. {@code @mapName} or {@code //config/map}.
     */
    DATA_DRIVEN,

    /**
     * The binding in scope is an {@code xsl:param}, whose value comes from the caller or the runtime.
     * A literal default is deliberately not used - see {@code XP-18}, {@code XP-19}.
     */
    PARAMETER,

    /**
     * The binding in scope is an {@code xsl:variable} whose value will not fold to a literal.
     */
    NON_LITERAL_BINDING,

    /**
     * No binding for the variable exists in this document, so it is presumably declared in an
     * imported stylesheet. The parser reads one document in isolation and does not follow imports.
     */
    IMPORTED,

    /**
     * The name matched more than one document. The candidates are carried on the finding; the parser
     * does not choose between them.
     */
    AMBIGUOUS,

    /**
     * The name or UUID resolved to no document at all. This is the reason that turns a silent runtime
     * failure into a visible one.
     */
    NOT_FOUND,

    /**
     * The expression could not be parsed. Recorded per expression, so a single malformed attribute
     * does not cost the whole document.
     */
    UNPARSEABLE,
}
