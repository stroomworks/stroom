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

package stroom.pipeline.shared;

/**
 * The kind of thing an XSLT refers to.
 * <p>
 * Only {@link #IMPORT} and {@link #DICTIONARY} identify a Stroom document. The remaining kinds name
 * something the XSLT alone cannot resolve to a document: a map name identifies a store only in
 * combination with the pipeline's configured references, and an endpoint is outside Stroom altogether.
 */
public enum XsltReferenceKind {

    /**
     * An {@code xsl:import} or {@code xsl:include} target, i.e. another XSLT document.
     */
    IMPORT,

    /**
     * A {@code stroom:dictionary()} argument, i.e. a Dictionary document.
     */
    DICTIONARY,

    /**
     * A map name read by {@code stroom:lookup()} or {@code stroom:bitmap-lookup()}.
     * <p>
     * Carries no document target. Which store, if any, a lookup reaches is a property of the
     * pipeline's configured references rather than of the XSLT.
     */
    REF_MAP_READ,

    /**
     * A map name written by the XSLT, i.e. the literal content of a {@code <map>} element in its
     * output. Carries no document target, for the same reason as {@link #REF_MAP_READ}.
     */
    REF_MAP_WRITE,

    /**
     * An external endpoint contacted by {@code stroom:http-call()} or {@code stroom:fetch-json()}.
     */
    HTTP,

    /**
     * An expression that could not be analysed, so whether it refers to anything is unknown.
     * <p>
     * Deliberately not one of the kinds above. Attributing a failed compile to, say,
     * {@link #REF_MAP_READ} would assert a map read that may not exist - a false positive, and the worse
     * of the two ways to be wrong. This kind claims only "there is something here I could not read",
     * which is true and is what an author needs told.
     */
    UNANALYSED,
}
