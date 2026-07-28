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

package stroom.graphdb.impl;

import stroom.query.language.functions.Val;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The single definition of how a property value is turned into the bytes a {@link GraphPropertyIndex} anchor is
 * keyed on.
 *
 * <p>This exists to be shared rather than because it is complicated. Anchors are written from two places - the
 * ingest filter, which holds the raw text of a property, and merge, which holds a decoded {@link Val} - and an
 * anchor written by one must be byte-identical to the same anchor written by the other, or a merged graph will
 * answer property-anchored queries differently from a directly-ingested one. Keeping both callers on this method
 * means they cannot drift apart.</p>
 *
 * <p>It is also the place any future change to anchor encoding belongs. Today values are indexed on their
 * lexical text, which is what the query side seeks on (a query literal's own source text), so the two agree.
 * Indexing a canonical form of a typed value instead would need this method, the query-side seek and a rebuild of
 * every existing index to change together - which is precisely why they are funnelled through one place.</p>
 */
public final class GraphAnchorEncoding {

    private GraphAnchorEncoding() {
        // Static utility.
    }

    /**
     * Encodes raw property text as anchor key bytes.
     *
     * <p><b>Preconditions:</b> {@code value} is not null.
     * <b>Postconditions:</b> returns the anchor bytes for {@code value}.
     * <b>Null status:</b> {@code value} is not nullable; the return value is never null.
     *
     * @param value the property's text as ingested.
     * @return the bytes to key an anchor on.
     */
    public static byte[] anchorValueBytes(final String value) {
        Objects.requireNonNull(value, "value must not be null");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes a decoded property value as anchor key bytes, identically to {@link #anchorValueBytes(String)} for
     * the same logical value.
     *
     * <p><b>Preconditions:</b> {@code value} is not null.
     * <b>Postconditions:</b> returns the anchor bytes for {@code value}.
     * <b>Null status:</b> {@code value} is not nullable; the return value is never null.
     *
     * @param value the property's decoded value.
     * @return the bytes to key an anchor on.
     */
    public static byte[] anchorValueBytes(final Val value) {
        Objects.requireNonNull(value, "value must not be null");
        return anchorValueBytes(value.toString());
    }
}
