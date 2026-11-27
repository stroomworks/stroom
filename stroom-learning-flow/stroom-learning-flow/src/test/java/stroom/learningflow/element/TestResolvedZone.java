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

import stroom.learningflow.shared.TimeZoneSource;
import stroom.meta.api.AttributeMap;
import stroom.pipeline.state.MetaDataProvider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests how the time zone for reading timestamps that carry no offset is chosen: the stream's header if it
/// has one, otherwise the pipeline's property, otherwise UTC. An invalid value is reported, not ignored.
class TestResolvedZone {

    @Test
    void theStreamsOwnHeaderWins() {
        // The sender knows best, because it has said what zone its data is written in
        final ResolvedZone zone = ResolvedZone.of("Europe/London", "America/New_York");

        assertThat(zone.id()).isEqualTo("Europe/London");
        assertThat(zone.source()).isEqualTo(TimeZoneSource.HEADER);
        assertThat(zone.problem()).isNull();
    }

    @Test
    void thePipelinesPropertyIsUsedWhereTheStreamSaysNothing() {
        final ResolvedZone zone = ResolvedZone.of(null, "America/New_York");

        assertThat(zone.id()).isEqualTo("America/New_York");
        assertThat(zone.source()).isEqualTo(TimeZoneSource.PIPELINE);
        assertThat(zone.problem()).isNull();
    }

    @Test
    void utcWhereNeitherIsSet() {
        final ResolvedZone zone = ResolvedZone.of(null, null);

        assertThat(zone.id()).isEqualTo("UTC");
        assertThat(zone.source()).isEqualTo(TimeZoneSource.DEFAULT);
        assertThat(zone.problem()).describedAs("nothing was asked for, so nothing is wrong").isNull();
    }

    @Test
    void aHeaderThatIsNotAZoneIsReportedRatherThanIgnored() {
        // Otherwise a misspelt zone would silently put every event's time hours out
        final ResolvedZone zone = ResolvedZone.of("Europe/Londn", null);

        assertThat(zone.id()).isEqualTo("UTC");
        assertThat(zone.source()).isEqualTo(TimeZoneSource.DEFAULT);
        assertThat(zone.problem())
                .contains("Europe/Londn")
                .contains("is not a time zone")
                .contains("read as UTC");
    }

    @Test
    void aBadHeaderFallsBackToThePipelineAndSaysSo() {
        // A bad header from the sender is not a reason to stop translating
        final ResolvedZone zone = ResolvedZone.of("nonsense", "Europe/London");

        assertThat(zone.id()).isEqualTo("Europe/London");
        assertThat(zone.source()).isEqualTo(TimeZoneSource.PIPELINE);
        assertThat(zone.problem())
                .contains("nonsense")
                .contains("pipeline's time zone is being used instead");
    }

    @Test
    void aBadPropertyIsReportedToo() {
        final ResolvedZone zone = ResolvedZone.of(null, "Mars/Olympus_Mons");

        assertThat(zone.id()).isEqualTo("UTC");
        assertThat(zone.problem()).contains("Mars/Olympus_Mons").contains("is not a time zone");
    }

    @Test
    void anOffsetIsAZoneToo() {
        // Not every sender has an IANA zone to hand
        assertThat(ResolvedZone.of("+01:00", null).id()).isEqualTo("+01:00");
        assertThat(ResolvedZone.of("Z", null).source()).isEqualTo(TimeZoneSource.HEADER);
    }

    @Test
    void theZoneIsAddedToTheMetadataWithoutLosingWhatTheSenderSaid() {
        // The resolved zone is added under its own key. Overwriting the sender's header with it would lose the
        // evidence of why a date came out as it did
        final AttributeMap attributes = new AttributeMap();
        attributes.put(ResolvedZone.HEADER, "Europe/Londn");

        final MetaDataProvider zoned =
                ZonedMetaData.wrapping(holding(attributes), ResolvedZone.of("Europe/Londn", "Europe/London"));

        assertThat(zoned.get(ResolvedZone.KEY)).isEqualTo("Europe/London");
        assertThat(zoned.get(ResolvedZone.HEADER))
                .describedAs("the sender's own header is left as it was")
                .isEqualTo("Europe/Londn");
    }

    @Test
    void wrappingTwiceDoesNotStackTheZones() {
        // One pipeline scope processes one stream after another, and each stream must use its own zone rather
        // than the first stream's
        final MetaDataProvider first = ZonedMetaData.wrapping(holding(new AttributeMap()),
                ResolvedZone.of("Europe/London", null));
        final MetaDataProvider second =
                ZonedMetaData.wrapping(first, ResolvedZone.of("America/New_York", null));

        assertThat(second.get(ResolvedZone.KEY)).isEqualTo("America/New_York");
    }

    /// Creates a metadata provider over a fixed map, standing in for the one a pipeline builds.
    ///
    /// @param attributes The stream's attributes.
    /// @return A provider that reads from `attributes`.
    private static MetaDataProvider holding(final AttributeMap attributes) {
        return new MetaDataProvider() {
            @Override
            public String get(final String key) {
                return attributes.get(key);
            }

            @Override
            public AttributeMap getMetaData() {
                return attributes;
            }
        };
    }

    @Test
    void blanksAreNotValues() {
        assertThat(ResolvedZone.of("  ", "  ").source()).isEqualTo(TimeZoneSource.DEFAULT);
        assertThat(ResolvedZone.of("  ", "  ").problem())
                .describedAs("a blank is nothing asked for, not something wrong")
                .isNull();
    }
}
