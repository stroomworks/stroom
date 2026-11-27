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

package stroom.learningflow.element;

import stroom.meta.api.AttributeMap;
import stroom.pipeline.state.MetaDataProvider;

/// Wraps the stream's metadata to add the resolved time zone.
///
/// A learned stylesheet reads the zone with `stroom:meta('ResolvedTimeZone')` and passes it to
/// `stroom:format-date`, so that a timestamp with no offset is read in the zone the feed is actually in.
/// Resolving the zone here rather than in the translation means a zone change takes effect without
/// relearning, and the model is never asked to assume one.
///
/// **The sender's own `TimeZone` attribute is left unchanged.** What the sender said and what was
/// resolved from it are different things, and overwriting one with the other would lose the evidence for
/// why a date came out as it did.
public class ZonedMetaData implements MetaDataProvider {

    private final MetaDataProvider delegate;
    private final ResolvedZone zone;

    /// @param delegate The stream's metadata as the pipeline builds it.
    /// @param zone     The zone this stream's timestamps are read in.
    public ZonedMetaData(final MetaDataProvider delegate, final ResolvedZone zone) {
        this.delegate = delegate;
        this.zone = zone;
    }

    /// Adds the zone to a provider, replacing any zone added before rather than wrapping it again, because
    /// one pipeline scope handles one stream after another and each must carry its own zone.
    ///
    /// @param provider The provider as it stands; null where nothing has set one.
    /// @param zone     The zone this stream's timestamps are read in.
    /// @return A provider that answers `ResolvedTimeZone`, or null where there was nothing to add to.
    public static MetaDataProvider wrapping(final MetaDataProvider provider, final ResolvedZone zone) {
        if (provider == null) {
            return null;
        }
        return new ZonedMetaData(provider instanceof final ZonedMetaData zoned
                ? zoned.delegate
                : provider, zone);
    }

    @Override
    public AttributeMap getMetaData() {
        final AttributeMap attributes = delegate.getMetaData();
        attributes.put(ResolvedZone.KEY, zone.id());
        return attributes;
    }

    @Override
    public String get(final String key) {
        return ResolvedZone.KEY.equals(key)
                ? zone.id()
                : delegate.get(key);
    }
}
