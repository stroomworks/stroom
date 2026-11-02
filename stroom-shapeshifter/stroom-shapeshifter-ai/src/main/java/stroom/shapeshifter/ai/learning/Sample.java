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

package stroom.shapeshifter.ai.learning;

import stroom.meta.shared.MetaFields;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an attempt is about: the input to learn from and the stream's feed and type (A29, A126).
 * <p>
 * The model sees the feed and the type and nothing else of the stream's attributes, so the attribute
 * map's sender-set entries — hostnames, paths, tokens — never travel. Both text and
 * values are subject to the redaction of A17 before they reach a model; that redaction is not yet built,
 * so today they travel as they arrive — and the document says nothing about it, since a setting that
 * promised redaction while none happened was worse than no setting (design 02 §6.1).
 *
 * @param text    The input sample, as the first element of the chain will receive it.
 * @param headers The feed and type the stream carries, in that order.
 */
public record Sample(String text, Map<String, String> headers) {

    /**
     * What the model is shown of a stream's attributes: its feed and its type.
     */
    public static final List<String> SHOWN = List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE);

    public Sample {
        // Order is kept: a prompt lists the values in order, strongest signal first.
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
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
