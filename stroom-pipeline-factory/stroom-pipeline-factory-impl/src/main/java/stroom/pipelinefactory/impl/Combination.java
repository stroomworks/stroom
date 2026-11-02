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

package stroom.pipelinefactory.impl;

import stroom.meta.shared.MetaFields;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One combination of a factory's key values, which is one pipeline (design 06 §3.2): each key field
/// and the value streams carry for it, as the meta table holds it. Feed and type are names; a pipeline
/// is its UUID, since that is what a stream records and what a filter matches.
///
/// @param values The key's fields and their values, in key order.
public record Combination(Map<String, String> values) {

    public Combination {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /// @param feed The feed's name.
    /// @param type The stream type's name.
    /// @return The combination of a feed and a type, the default key's.
    public static Combination ofFeedAndType(final String feed, final String type) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(MetaFields.FEED.getFldName(), feed);
        values.put(MetaFields.TYPE.getFldName(), type);
        return new Combination(values);
    }

    /// @param key A factory's key.
    /// @return Whether this combination has a value for every field of the key, and for nothing else.
    public boolean fits(final List<String> key) {
        return values.keySet().equals(new LinkedHashSet<>(key))
               && values.values().stream().allMatch(Objects::nonNull);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
