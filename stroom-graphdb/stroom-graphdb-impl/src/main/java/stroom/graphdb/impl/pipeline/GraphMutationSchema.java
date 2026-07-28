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

package stroom.graphdb.impl.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Access to the shipped {@code graph-mutation:1} XSD.
 *
 * <p>The schema is a resource in this module's jar rather than a test fixture, so there is exactly one copy of it:
 * the one the product ships, the one the tests validate against, and the one an administrator retrieves to create
 * the {@code XMLSchema} document a {@code SchemaFilter} needs. It previously existed only under
 * {@code src/test/resources}, which meant the vocabulary a pipeline author had to conform to was not actually
 * shipped anywhere they could get at it.</p>
 *
 * <p>{@link GraphFilter} does <b>not</b> use this. It dispatches on SAX local names and will ingest a document
 * this schema would reject; validation is a separate, opt-in pipeline element. Keeping the two independent is
 * deliberate - a filter that silently depended on a schema resource would fail in a way that looked like a data
 * problem.</p>
 */
public final class GraphMutationSchema {

    /** The vocabulary's target namespace, as it appears in an instance document's {@code xmlns}. */
    public static final String NAMESPACE_URI = "graph-mutation:1";

    /**
     * The system id to register the {@code XMLSchema} document under, and the value an instance document's
     * {@code xsi:schemaLocation} must pair with {@link #NAMESPACE_URI}.
     */
    public static final String SYSTEM_ID = "graph-mutation-v1.0.xsd";

    private static final String RESOURCE_PATH = "/stroom/graphdb/graph-mutation-v1.0.xsd";

    private GraphMutationSchema() {
        // Static utility.
    }

    /**
     * The XSD's location on the classpath.
     *
     * <p><b>Postconditions:</b> returns a readable URL; never null, because the resource is packaged with this
     * class and its absence is a build error rather than a runtime condition to handle.
     * <b>Null status:</b> the return value is never null.
     *
     * @return the schema resource's URL.
     */
    public static URL url() {
        final URL url = GraphMutationSchema.class.getResource(RESOURCE_PATH);
        if (url == null) {
            throw new IllegalStateException("Missing packaged resource: " + RESOURCE_PATH);
        }
        return url;
    }

    /**
     * The XSD's text, for display or for pasting into an {@code XMLSchema} document.
     *
     * <p><b>Postconditions:</b> returns the schema source as UTF-8 text.
     * <b>Null status:</b> the return value is never null.
     *
     * @return the schema source.
     */
    public static String text() {
        try (final InputStream inputStream = GraphMutationSchema.class.getResourceAsStream(RESOURCE_PATH)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing packaged resource: " + RESOURCE_PATH);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read " + RESOURCE_PATH, e);
        }
    }
}
