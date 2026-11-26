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
import stroom.util.shared.NullSafe;

import java.time.DateTimeException;
import java.time.ZoneId;

/// The time zone that timestamps with no offset are read in, and where that zone came from.
///
/// The model is never asked to choose or assume a zone. The zone is resolved here, written into the
/// stream's metadata as [#KEY], and read by the learned stylesheet with `stroom:meta('ResolvedTimeZone')`.
/// Resolving it outside the translation means changing a feed's zone needs no relearning.
///
/// @param zone    The zone to read timestamps in.
/// @param source  Where the zone came from, which the Learning Status tab shows so that a surprising date
///                can be traced to what set the zone.
/// @param problem What was wrong with the zones given, or null where nothing was. An invalid zone is
///                reported rather than quietly treated as UTC, because otherwise a feed whose zone is
///                misspelt would be hours out with no warning.
public record ResolvedZone(ZoneId zone, TimeZoneSource source, String problem) {

    /// The metadata key the resolved zone is written under. The sender's own `TimeZone` attribute is never
    /// overwritten, because the resolved zone is not necessarily what the sender said.
    public static final String KEY = "ResolvedTimeZone";

    /// The header in which a sender may state its zone, as an IANA zone id such as `Europe/London`.
    public static final String HEADER = "TimeZone";

    /// Resolves a stream's zone: the header it carries, else the pipeline's property, else UTC.
    ///
    /// @param header   The stream's `TimeZone` header, or null where it carries none.
    /// @param property The Smart Pipeline element's `timeZone` property, or null where it is not set.
    /// @return The zone, where it came from, and any problem with the zones that were given.
    public static ResolvedZone of(final String header, final String property) {
        final ZoneId fromHeader = parse(header);
        if (fromHeader != null) {
            return new ResolvedZone(fromHeader, TimeZoneSource.HEADER, null);
        }
        // A header that was given but is not a valid zone is reported, and resolution carries on to the
        // property: a sender's mistake is not a reason to stop translating.
        final String headerProblem = NullSafe.isNonBlankString(header)
                ? "The stream's " + HEADER + " header is \"" + header.trim()
                  + "\", which is not a time zone. "
                : null;
        final ZoneId fromProperty = parse(property);
        if (fromProperty != null) {
            return new ResolvedZone(fromProperty, TimeZoneSource.PIPELINE, headerProblem == null
                    ? null
                    : headerProblem + "The pipeline's time zone is being used instead.");
        }
        final String propertyProblem = NullSafe.isNonBlankString(property)
                ? "The pipeline's time zone is \"" + property.trim() + "\", which is not a time zone. "
                : null;
        final String problem = (headerProblem == null
                ? ""
                : headerProblem) + (propertyProblem == null
                ? ""
                : propertyProblem);
        return new ResolvedZone(ZoneId.of("UTC"), TimeZoneSource.DEFAULT, problem.isEmpty()
                ? null
                : problem + "Times with no offset are being read as UTC.");
    }

    /// The zone's id, as written into the metadata.
    ///
    /// @return The zone id, e.g. `Europe/London`.
    public String id() {
        return zone.getId();
    }

    /// @param value A zone id.
    /// @return The zone, or null where the value is blank or not a valid zone id.
    private static ZoneId parse(final String value) {
        if (NullSafe.isBlankString(value)) {
            return null;
        }
        try {
            return ZoneId.of(value.trim());
        } catch (final DateTimeException e) {
            return null;
        }
    }
}
