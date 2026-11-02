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

package stroom.pipelinefactory.shared;

import stroom.meta.shared.MetaFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// What makes one pipeline, and how its values are written into names (design 06 §3.1).
///
/// A factory's key is a list of meta fields, and one combination of their values is one pipeline. The
/// fields a key may name are those a processor filter can query and that sort streams into kinds rather
/// than single out one stream (A119): feed, type and the pipeline that wrote a stream. Ids, times,
/// counts and sizes are queryable too, but a pipeline per value of one is a pipeline per stream.
///
/// A pattern names a key field as `${field}`, in lower case: `${feed}`, `${type}`, `${pipeline}`.
public final class PipelineFactoryKey {

    /// The fields a key may name, in the order the key picker offers them.
    public static final List<String> FIELDS = List.of(
            MetaFields.FEED.getFldName(),
            MetaFields.TYPE.getFldName(),
            MetaFields.PIPELINE.getFldName());

    /// A new factory's key: one pipeline per feed and type (A104).
    public static final List<String> DEFAULT = List.of(
            MetaFields.FEED.getFldName(),
            MetaFields.TYPE.getFldName());

    private static final String OPEN = "${";
    private static final String CLOSE = "}";

    private PipelineFactoryKey() {
        // Constants and functions only.
    }

    /// @param field A key field, e.g. `Feed`.
    /// @return The name a pattern uses for it, e.g. `feed`.
    public static String placeholder(final String field) {
        return field.toLowerCase(Locale.ROOT);
    }

    /// @param pattern A pattern, e.g. `Feeds/${feed}`; null is read as empty.
    /// @return The names it uses, in the order they appear, e.g. `[feed]`.
    /// @throws IllegalArgumentException If a `${` is not closed.
    public static List<String> placeholders(final String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> names = new ArrayList<>();
        int from = 0;
        int open = pattern.indexOf(OPEN, from);
        while (open >= 0) {
            final int close = pattern.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                throw new IllegalArgumentException("'" + pattern + "' opens a ${ it does not close");
            }
            names.add(pattern.substring(open + OPEN.length(), close));
            from = close + CLOSE.length();
            open = pattern.indexOf(OPEN, from);
        }
        return names;
    }

    /// @param pattern A pattern whose names have been checked against the key; null is read as empty.
    /// @param values  The combination's values, by key field, e.g. `{Feed=SYSLOG, Type=Raw Events}`.
    /// @return The pattern with each name replaced by its value.
    /// @throws IllegalArgumentException If the pattern names a field the values lack.
    public static String substitute(final String pattern, final Map<String, String> values) {
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        final StringBuilder made = new StringBuilder();
        int from = 0;
        int open = pattern.indexOf(OPEN, from);
        while (open >= 0) {
            final int close = pattern.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                throw new IllegalArgumentException("'" + pattern + "' opens a ${ it does not close");
            }
            final String name = pattern.substring(open + OPEN.length(), close);
            made.append(pattern, from, open).append(valueOf(name, values, pattern));
            from = close + CLOSE.length();
            open = pattern.indexOf(OPEN, from);
        }
        return made.append(pattern.substring(from)).toString();
    }

    private static String valueOf(final String name,
                                  final Map<String, String> values,
                                  final String pattern) {
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            if (placeholder(entry.getKey()).equals(name)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException("'" + pattern + "' names ${" + name + "}, which is not in the key");
    }
}
