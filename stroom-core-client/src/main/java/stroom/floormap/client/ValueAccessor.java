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

package stroom.floormap.client;

import stroom.floormap.shared.ValueFormat;

/**
 * Format-independent interface for reading, writing, and serialising
 * floor map entry values.
 *
 * <p>Each {@link ValueFormat} has a corresponding implementation:
 * {@link JsonValueAccessor} for JSON and {@link XmlValueAccessor} for
 * XML. Callers obtain an accessor via {@link #forFormat(ValueFormat)}
 * and then use the same API regardless of the underlying format.</p>
 *
 * <h3>Namespace support (XML)</h3>
 * <p>The current XML implementation ({@link XmlValueAccessor}) does
 * <b>not</b> support XML namespaces. Paths match elements by local
 * name only (e.g. {@code /entry/type}). This is intentional: floor
 * map values are user-defined and authored by Stroom, making
 * namespace-qualified XML unlikely in practice.</p>
 *
 * <p>If namespace support is needed in future, the extension path is:
 * add a namespace-prefix-to-URI mapping to the settings, use
 * namespace-aware path syntax ({@code /ns:entry/ns:type}), and
 * switch {@code XmlValueAccessor} to
 * {@code getElementsByTagNameNS()} / {@code getAttributeNS()}.
 * GWT's {@code com.google.gwt.xml.client} API already provides
 * these methods, so no architectural changes to this interface
 * are required.</p>
 *
 * @see ParsedValue
 * @see JsonValueAccessor
 * @see XmlValueAccessor
 */
public interface ValueAccessor {

    /**
     * Parses a raw value string into a {@link ParsedValue}.
     *
     * @param raw the raw string (JSON or XML); may be {@code null}
     * @return the parsed value, or {@code null} if the input is
     *         {@code null}, empty, or unparseable
     */
    ParsedValue parse(String raw);

    /**
     * Creates a new empty value suitable for populating with
     * {@link #setString} and {@link #setArray}.
     *
     * <p>For JSON this creates an empty {@code {}}. For XML this
     * creates a document with an empty root element.</p>
     *
     * @param rootName the name of the root element (used by XML;
     *                 ignored by JSON)
     * @return a new, empty {@link ParsedValue}
     */
    ParsedValue createEmpty(String rootName);

    /**
     * Reads a string value at the given path.
     *
     * @param value the parsed value to read from
     * @param path  the format-specific path (e.g. {@code ".type"} for
     *              JSON, {@code "/entry/type"} for XML)
     * @return the string value, or {@code null} if not found
     */
    String getString(ParsedValue value, String path);

    /**
     * Writes a string value at the given path.
     *
     * @param value     the parsed value to write to
     * @param path      the format-specific path
     * @param textValue the string to write; if {@code null}, the
     *                  field is removed
     */
    void setString(ParsedValue value, String path, String textValue);

    /**
     * Reads a numeric array at the given path.
     *
     * <p>For JSON, this expects a {@code JSONArray} of numbers. For
     * XML, this expects comma-separated numbers in the element text
     * content.</p>
     *
     * @param value the parsed value to read from
     * @param path  the format-specific path
     * @return the numeric array, or {@code null} if not found or
     *         malformed
     */
    double[] getArray(ParsedValue value, String path);

    /**
     * Writes a numeric array at the given path.
     *
     * <p>For JSON, this writes a {@code JSONArray} of numbers. For
     * XML, this writes comma-separated numbers as element text
     * content.</p>
     *
     * @param value   the parsed value to write to
     * @param path    the format-specific path
     * @param numbers the numeric array to write
     */
    void setArray(ParsedValue value, String path, double[] numbers);

    /**
     * Serialises the parsed value back to a string.
     *
     * @param value the parsed value to serialise
     * @return the serialised string (JSON or XML)
     */
    String serialize(ParsedValue value);

    /**
     * Returns {@code true} if the given raw string looks like it
     * could be parsed by this accessor. Used for quick format
     * detection (e.g. starts with {@code "{"} for JSON or
     * {@code "<"} for XML).
     * @param raw the raw value string
     * @return {@code true} if this accessor can likely parse it
     */
    boolean canParse(String raw);

    /**
     * Returns the appropriate {@link ValueAccessor} for the given
     * format.
     *
     * @param format the value format; must not be {@code null}
     * @return the accessor instance (singleton)
     */
    static ValueAccessor forFormat(final ValueFormat format) {
        return switch (format) {
            case JSON -> JsonValueAccessor.INSTANCE;
            case XML -> XmlValueAccessor.INSTANCE;
        };
    }
}
