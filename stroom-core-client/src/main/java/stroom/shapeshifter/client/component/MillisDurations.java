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

package stroom.shapeshifter.client.component;

import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import java.util.List;

/// A document's durations, which it keeps in milliseconds, as a [SimpleDuration] for a
/// [stroom.widget.duration.client.DurationPicker] to show, and back. The picker offers seconds to years,
/// so a duration is shown in the largest of seconds, minutes, hours, days and weeks that holds it
/// exactly; months and years are only ever what a person chose (approximate, as
/// [SimpleDuration#getApproxMillis()] says).
public final class MillisDurations {

    // Largest first; months and years aren't exact, so a duration read is never shown in them
    private static final List<TimeUnit> UNITS = List.of(
            TimeUnit.WEEKS,
            TimeUnit.DAYS,
            TimeUnit.HOURS,
            TimeUnit.MINUTES,
            TimeUnit.SECONDS);

    private MillisDurations() {
        // Static helpers only
    }

    /// The duration to show for a number of milliseconds: in the largest unit that holds it exactly,
    /// else in whole seconds, the nearest, at least one.
    ///
    /// @param millis The duration in milliseconds.
    /// @return The duration to show.
    public static SimpleDuration toDuration(final long millis) {
        for (final TimeUnit unit : UNITS) {
            final long size = unitMillis(unit);
            if (millis >= size && millis % size == 0) {
                return new SimpleDuration(millis / size, unit);
            }
        }
        return new SimpleDuration(Math.max(1L, Math.round(millis / 1000.0)), TimeUnit.SECONDS);
    }

    /// The milliseconds for the duration shown. Where it is still the one shown for what was read, what
    /// was read is kept, so that a duration that is not whole seconds isn't changed by being shown.
    ///
    /// @param shown The duration the picker holds.
    /// @param read  The milliseconds that were read.
    /// @return The milliseconds to write.
    public static long toMillis(final SimpleDuration shown, final long read) {
        if (shown == null || shown.equals(toDuration(read))) {
            return read;
        }
        return shown.getApproxMillis();
    }

    private static long unitMillis(final TimeUnit unit) {
        return new SimpleDuration(1L, unit).getApproxMillis();
    }
}
