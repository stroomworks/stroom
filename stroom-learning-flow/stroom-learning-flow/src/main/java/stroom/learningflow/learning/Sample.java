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

package stroom.learningflow.learning;

import stroom.learningflow.stage.Input;
import stroom.learningflow.transformation.StreamFacts;
import stroom.meta.shared.MetaFields;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an attempt is about: the input to learn from and the stream's feed and type (A29, A126).
 * <p>
 * The model sees the feed and the type and nothing else of the stream's attributes, so the attribute
 * map's sender-set entries — hostnames, paths, tokens — never travel. The text and the values travel as
 * they arrive: **samples are never redacted** (A146, which supersedes A17 and A38). Redaction good enough
 * to protect data would itself need a model, and a model asked to translate redacted data loses the
 * context it translates by, so a feed is pointed at a model only where its data may go to that model.
 *
 * @param text    The input sample, as the first element of the chain will receive it.
 * @param headers The feed and type the stream carries, in that order.
 * @param facts   The stream it came from.
 * @param cutFrom How many bytes of the stream the sample was taken from, where it is less than all of
 *                them; zero where the sample is the whole of what was read. A person reading "couldn't
 *                write the parser" is owed the fact that the model saw eight kilobytes of a two-megabyte
 *                stream (supervisor visibility item 3, audit finding S4).
 * @param beyondPrefix Whether the stream is longer than the prefix that was read as text at all
 *                ({@code Input#PREFIX_BYTES}). A prefix of a single XML or JSON document is not a
 *                document, so a stream like that cannot be sampled: it has to be refused with a reason
 *                rather than passed over in silence (N2 option E).
 */
public record Sample(String text, Map<String, String> headers, StreamFacts facts, int cutFrom,
                     boolean beyondPrefix) {

    public Sample {
        // Order is kept: a prompt lists the values in order, strongest signal first.
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        facts = facts == null
                ? StreamFacts.none()
                : facts;
    }

    /// @param text    The sample.
    /// @param headers What the model is shown of the stream's attributes.
    /// @param facts   The stream it came from.
    public Sample(final String text, final Map<String, String> headers, final StreamFacts facts) {
        this(text, headers, facts, 0, false);
    }

    /// @param text    The sample.
    /// @param headers What the model is shown of the stream's attributes.
    /// @param facts   The stream it came from.
    /// @param cutFrom How many bytes the sample was taken from.
    public Sample(final String text, final Map<String, String> headers, final StreamFacts facts,
                  final int cutFrom) {
        this(text, headers, facts, cutFrom, false);
    }

    /// @param beyondPrefix Whether the stream runs past what was read as text.
    /// @return The same sample, knowing that.
    public Sample beyondPrefix(final boolean beyondPrefix) {
        return new Sample(text, headers, facts, cutFrom, beyondPrefix);
    }

    /// @param wholeBytes How many bytes the sample was taken from.
    /// @return The same sample, knowing how much of its stream it is.
    public Sample cutFrom(final int wholeBytes) {
        return new Sample(text, headers, facts, Math.max(0, wholeBytes - Input.bytes(text)) == 0
                ? 0
                : wholeBytes, beyondPrefix);
    }

    /// How many bytes of the stream the model was not shown; zero where it saw all that was read.
    public int notShown() {
        return cutFrom == 0
                ? 0
                : Math.max(0, cutFrom - Input.bytes(text));
    }

    /// @param text    The sample.
    /// @param headers What the model is shown of the stream's attributes.
    public Sample(final String text, final Map<String, String> headers) {
        this(text, headers, StreamFacts.none());
    }

    /// @param facts The stream this came from.
    /// @return The same sample, knowing its stream.
    public Sample from(final StreamFacts facts) {
        return new Sample(text, headers, facts, cutFrom, beyondPrefix);
    }

    public static Sample of(final String text) {
        return new Sample(text, Map.of());
    }

    /**
     * @param feed The stream's feed, where it has one.
     * @param type The stream's type, where it has one.
     */
    public static Sample of(final String text, final String feed, final String type) {
        final Map<String, String> headers = new LinkedHashMap<>();
        if (feed != null) {
            headers.put(MetaFields.FIELD_FEED, feed);
        }
        if (type != null) {
            headers.put(MetaFields.FIELD_TYPE, type);
        }
        return new Sample(text, headers);
    }
}
