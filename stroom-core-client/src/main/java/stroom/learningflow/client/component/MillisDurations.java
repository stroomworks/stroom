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

package stroom.learningflow.client.component;

import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import java.util.List;

/// Converts the durations a Learning Flow document stores in milliseconds to a [SimpleDuration] for a
/// [stroom.widget.duration.client.DurationPicker] to show, and back again.
///
/// The picker offers units from seconds to years. A stored duration is shown in the largest of weeks, days,
/// hours, minutes and seconds that divides it exactly. Months and years are never chosen here because they
/// are not a fixed length (see [SimpleDuration#getApproxMillis()]); they appear only if a person picks them.
public final class MillisDurations {

    // Largest first. Months and years are left out because they are not a fixed number of milliseconds.
    private static final List<TimeUnit> UNITS = List.of(
            TimeUnit.WEEKS,
            TimeUnit.DAYS,
            TimeUnit.HOURS,
            TimeUnit.MINUTES,
            TimeUnit.SECONDS);

    private MillisDurations() {
        // Static helpers only
    }

    /// Gets the duration to show for a number of milliseconds. It is expressed in the largest unit that
    /// divides it exactly; failing that, it is rounded to the nearest whole second, with a minimum of one.
    ///
    /// @param millis The duration in milliseconds.
    /// @return The duration to show in the picker.
    public static SimpleDuration toDuration(final long millis) {
        for (final TimeUnit unit : UNITS) {
            final long size = unitMillis(unit);
            if (millis >= size && millis % size == 0) {
                return new SimpleDuration(millis / size, unit);
            }
        }
        return new SimpleDuration(Math.max(1L, Math.round(millis / 1000.0)), TimeUnit.SECONDS);
    }

    /// Gets the milliseconds to store for the duration in the picker. If the person has not changed the
    /// picker (or it is empty), the originally read value is returned unchanged, so that a stored duration
    /// that is not a whole number of seconds is not rounded just by opening and saving the document.
    ///
    /// @param shown The duration the picker holds, or null.
    /// @param read  The milliseconds originally read from the document.
    /// @return The milliseconds to write back to the document.
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
