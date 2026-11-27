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

/// What a learning attempt is about: the input to learn from, and the stream's feed and type.
///
/// The model is shown the feed and the type and none of the stream's other attributes, so values set by the
/// sender (hostnames, paths, tokens) are never sent. The text and those values are sent as they arrive:
/// **samples are never redacted**. Redaction good enough to protect data would itself need a model, and a
/// model asked to translate redacted data loses the context it needs, so a feed should be pointed at a model
/// only where its data may be sent to that model.
///
/// @param text         The input sample, as the first element of the chain will receive it.
/// @param headers      The feed and type the stream carries, in that order.
/// @param facts        The stream it came from.
/// @param cutFrom      How many bytes the sample was cut from, where the sample is less than all of them;
///                     zero where the sample is the whole of what was read. A person reading that the model
///                     "couldn't write the parser" needs to know that it saw, say, eight kilobytes of a
///                     two-megabyte stream.
/// @param beyondPrefix Whether the stream is longer than the prefix that was read as text at all
///                     (`Input#PREFIX_BYTES`). A prefix of a single XML or JSON document is not a complete
///                     document, so such a stream cannot be sampled; it has to be refused with a reason
///                     rather than skipped without explanation.
public record Sample(String text, Map<String, String> headers, StreamFacts facts, int cutFrom,
                     boolean beyondPrefix) {

    public Sample {
        // Keep the order, because a prompt lists the values in order, strongest hint first.
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
    /// @return A copy of this sample with that flag set.
    public Sample beyondPrefix(final boolean beyondPrefix) {
        return new Sample(text, headers, facts, cutFrom, beyondPrefix);
    }

    /// @param wholeBytes How many bytes the sample was cut from.
    /// @return A copy of this sample recording that size, or zero if the sample is all of it.
    public Sample cutFrom(final int wholeBytes) {
        return new Sample(text, headers, facts, Math.max(0, wholeBytes - Input.bytes(text)) == 0
                ? 0
                : wholeBytes, beyondPrefix);
    }

    /// @return How many bytes of what was read the model was not shown; zero where it saw all of it.
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
    /// @return A copy of this sample with the stream's facts.
    public Sample from(final StreamFacts facts) {
        return new Sample(text, headers, facts, cutFrom, beyondPrefix);
    }

    public static Sample of(final String text) {
        return new Sample(text, Map.of());
    }

    /// @param text The sample.
    /// @param feed The stream's feed, or null where it has none.
    /// @param type The stream's type, or null where it has none.
    /// @return A sample whose headers are the feed and type that are given.
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
