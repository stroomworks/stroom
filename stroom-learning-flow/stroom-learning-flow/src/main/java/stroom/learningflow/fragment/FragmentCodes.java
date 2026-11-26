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

package stroom.learningflow.fragment;

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
import java.util.function.BiConsumer;

/// Reads a fragment as text: its structure, the code each of its elements runs with, and the versions of
/// the documents all of that was read from. A snapshot, kept at each promotion so that a stream can be
/// reprocessed exactly as it was first processed, is a stored copy of this. A hand edit of the fragment
/// shows up as a version that differs from the snapshot's.
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

    /// Reads a fragment as it currently stands.
    ///
    /// @param fragment The fragment.
    /// @return Its structure, code and document versions.
    public Codes read(final DocRef fragment) {
        final PipelineDoc pipeline = pipelineStore.readDocument(fragment);
        final PipelineData data = pipeline.getPipelineData();
        final Map<String, String> code = new LinkedHashMap<>();
        final Map<String, String> versions = new LinkedHashMap<>();
        versions.put(fragment.getUuid(), pipeline.getVersion());
        eachReference(data, (element, referenced) -> {
            switch (referenced.getType()) {
                case XsltDoc.TYPE -> {
                    final XsltDoc xslt = xsltStore.readDocument(referenced);
                    code.put(element, xslt.getData());
                    versions.put(referenced.getUuid(), xslt.getVersion());
                }
                case TextConverterDoc.TYPE -> {
                    final TextConverterDoc converter = textConverterStore.readDocument(referenced);
                    code.put(element, converter.getData());
                    versions.put(referenced.getUuid(), converter.getVersion());
                }
                default -> {
                    // A document the fragment references that no element runs as code, such as a schema
                    // or a feed. A hand edit of the fragment's code does not change its version, so it is
                    // not recorded.
                }
            }
        });
        return new Codes(JsonUtil.writeValueAsString(data, false), code, versions);
    }

    /// Reads the stylesheets a fragment's elements run, for the check that a learned translation uses only
    /// permitted XSLT/XPath functions and instructions. [#read] does not record which code is a stylesheet
    /// and which a text converter, and only a stylesheet can call functions.
    ///
    /// @param fragment The fragment.
    /// @return A map from element id to the stylesheet it runs; empty where it runs none.
    public Map<String, String> stylesheets(final DocRef fragment) {
        final PipelineDoc pipeline = pipelineStore.readDocument(fragment);
        final Map<String, String> stylesheets = new LinkedHashMap<>();
        eachReference(pipeline.getPipelineData(), (element, referenced) -> {
            if (XsltDoc.TYPE.equals(referenced.getType())) {
                stylesheets.put(element, xsltStore.readDocument(referenced).getData());
            }
        });
        return stylesheets;
    }

    /// Calls the action for each document the fragment's own properties reference, with the id of the
    /// element whose property references it, in the order the properties are held. A property that
    /// references nothing is skipped.
    private static void eachReference(final PipelineData data, final BiConsumer<String, DocRef> action) {
        for (final PipelineProperty property : data.getAddedProperties()) {
            final DocRef referenced = property.getValue() == null
                    ? null
                    : property.getValue().getEntity();
            if (referenced != null) {
                action.accept(property.getElement(), referenced);
            }
        }
    }

    /// Reads just the document versions, for checking whether a fragment has changed since a snapshot was
    /// taken.
    ///
    /// @param fragment The fragment.
    /// @return A map from document UUID to version, for the fragment and each document its elements run
    /// as code.
    public Map<String, String> versions(final DocRef fragment) {
        return read(fragment).versions();
    }

    // --------------------------------------------------------------------------------

    /// A fragment as text.
    ///
    /// @param structure The pipeline data, as JSON.
    /// @param code      A map from element id to the code that element runs with.
    /// @param versions  A map from document UUID to the version this was read from.
    public record Codes(String structure, Map<String, String> code, Map<String, String> versions) {

        public Codes {
            code = Map.copyOf(code);
            versions = Map.copyOf(versions);
        }
    }
}
