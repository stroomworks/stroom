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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.util.json.JsonUtil;

import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/// What a fragment is made of, as text (A61, A70): its structure, the code each of its elements runs
/// with, and the versions of the documents all of that was read from. A snapshot is this, kept; a hand
/// edit is this, differing from the snapshot in a version (A72).
public final class FragmentCodes {

    private final PipelineStore pipelineStore;
    private final TextConverterStore textConverterStore;
    private final XsltStore xsltStore;

    @Inject
    public FragmentCodes(final PipelineStore pipelineStore,
                         final TextConverterStore textConverterStore,
                         final XsltStore xsltStore) {
        this.pipelineStore = pipelineStore;
        this.textConverterStore = textConverterStore;
        this.xsltStore = xsltStore;
    }

    /// Read a fragment as it stands.
    ///
    /// @param fragment The fragment.
    /// @return Its codes.
    public Codes read(final DocRef fragment) {
        final PipelineDoc pipeline = pipelineStore.readDocument(fragment);
        final PipelineData data = pipeline.getPipelineData();
        final Map<String, String> code = new LinkedHashMap<>();
        final Map<String, String> versions = new LinkedHashMap<>();
        versions.put(fragment.getUuid(), pipeline.getVersion());
        for (final PipelineProperty property : data.getAddedProperties()) {
            final DocRef referenced = property.getValue() == null
                    ? null
                    : property.getValue().getEntity();
            if (referenced == null) {
                continue;
            }
            switch (referenced.getType()) {
                case XsltDoc.TYPE -> {
                    final XsltDoc xslt = xsltStore.readDocument(referenced);
                    code.put(property.getElement(), xslt.getData());
                    versions.put(referenced.getUuid(), xslt.getVersion());
                }
                case TextConverterDoc.TYPE -> {
                    final TextConverterDoc converter = textConverterStore.readDocument(referenced);
                    code.put(property.getElement(), converter.getData());
                    versions.put(referenced.getUuid(), converter.getVersion());
                }
                default -> {
                    // A document the fragment references that no element runs as code: a schema, a
                    // feed. Its version is not what a hand edit of the fragment's code changes.
                }
            }
        }
        return new Codes(JsonUtil.writeValueAsString(data, false), code, versions);
    }

    /// The versions alone, for asking whether a fragment has moved since a snapshot was taken.
    ///
    /// @param fragment The fragment.
    /// @return Document uuid to version, for the fragment and each document its elements run as code.
    public Map<String, String> versions(final DocRef fragment) {
        return read(fragment).versions();
    }

    // --------------------------------------------------------------------------------

    /// A fragment as text.
    ///
    /// @param structure The pipeline data, as JSON.
    /// @param code      Element id to the code that element runs with.
    /// @param versions  Document uuid to the version of it this was read from.
    public record Codes(String structure, Map<String, String> code, Map<String, String> versions) {

        public Codes {
            code = Map.copyOf(code);
            versions = Map.copyOf(versions);
        }
    }
}
