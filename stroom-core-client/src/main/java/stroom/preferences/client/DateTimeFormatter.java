/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.preferences.client;

import stroom.query.api.UserTimeZone;
import stroom.query.api.UserTimeZone.Use;
import stroom.ui.config.shared.UserPreferences;
import stroom.util.shared.NullSafe;
import stroom.widget.customdatebox.client.MomentJs;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DateTimeFormatter {

    private final UserPreferencesManager userPreferencesManager;

    @Inject
    public DateTimeFormatter(final UserPreferencesManager userPreferencesManager) {
        this.userPreferencesManager = userPreferencesManager;
    }

    public String formatWithDuration(final Long ms) {
        if (ms == null) {
            return null;
        }

        final long now = System.currentTimeMillis();
        return format(ms) +
                " (" +
                MomentJs.humanise(ms - now, true) +
                ")";
    }

    public String format(final Long ms) {
        return format(ms, false);
    }

    /**
     * Formats a timestamp the same way {@link #format(Long)} does, but with the
     * seconds, any sub-second digits and the zone dropped.
     *
     * <p>For labels that have to be short — a chart axis, a scrubber pill — and
     * where the second and the zone are noise. Field order, separators and the
     * 12/24-hour choice all still come from the user's preference, so a shortened
     * label reads as the same format as the full one, not a different one.</p>
     *
     * @param ms the instant, or {@code null}
     * @return the formatted instant, or {@code null} when {@code ms} is null
     */
    public String formatShort(final Long ms) {
        return format(ms, true);
    }

    private String format(final Long ms, final boolean shortForm) {
        if (ms == null) {
            return null;
        }

        final TimeZoneSettings tz = getTimeZoneSettings();

        String pattern = shortForm
                ? dropSecondsAndZone(tz.pattern)
                : tz.pattern;

        // If UTC then just display the `Z` suffix.
        if (Use.UTC.equals(tz.use)) {
            pattern = pattern.replaceAll("Z", "[Z]");
        }
        // Ensure we haven't doubled up square brackets.
        pattern = pattern.replaceAll("\\[+", "[");
        pattern = pattern.replaceAll("]+", "]");

        // If UTC then just display the `Z` suffix.
        if (Use.UTC.equals(tz.use)) {
            pattern = pattern.replaceAll("Z", "[Z]");
        }
        // Ensure we haven't doubled up square brackets.
        pattern = pattern.replaceAll("\\[+", "[");
        pattern = pattern.replaceAll("]+", "]");

        return MomentJs.nativeToDateString(ms, tz.use.getDisplayValue(), pattern, tz.zoneId, tz.offsetMinutes);
    }

    /**
     * Format an epoch-millis timestamp as a human-readable relative time string
     * (e.g. "just now", "5 minutes ago", "yesterday") relative to the given
     * {@code nowMs} value.  Calendar day boundaries (for "yesterday" / "N days ago")
     * are computed in the user's preferred timezone.
     */
    public String formatRelative(final long timeMs, final long nowMs) {
        final long diff = nowMs - timeMs;

        if (diff < ONE_SECOND) {
            return "just now";
        }

        final long seconds = diff / ONE_SECOND;
        if (seconds < 60) {
            return seconds == 1
                    ? "a second ago"
                    : seconds + " seconds ago";
        }

        final long minutes = diff / ONE_MINUTE;
        if (minutes < 60) {
            return minutes == 1
                    ? "a minute ago"
                    : minutes + " minutes ago";
        }

        final long hours = diff / ONE_HOUR;
        if (hours < 24) {
            return hours == 1
                    ? "an hour ago"
                    : hours + " hours ago";
        }

        // Use timezone-aware calendar day computation.
        final TimeZoneSettings tz = getTimeZoneSettings();
        final int days = MomentJs.daysBetween(
                timeMs, nowMs, tz.use.getDisplayValue(), tz.zoneId, tz.offsetMinutes);

        if (days == 1) {
            return "yesterday";
        } else if (days >= 365) {
            final int years = days / 365;
            return years == 1
                    ? "a year ago"
                    : years + " years ago";
        } else {
            return days + " days ago";
        }
    }

    private TimeZoneSettings getTimeZoneSettings() {
        Use use = Use.UTC;
        String pattern = "YYYY-MM-DD[T]HH:mm:ss.SSSZ";
        int offsetMinutes = 0;
        String zoneId = "UTC";

        final UserPreferences userPreferences = userPreferencesManager.getCurrentUserPreferences();
        if (userPreferences != null) {
            if (NullSafe.isNonBlankString(userPreferences.getDateTimePattern())) {

                final UserTimeZone timeZone = userPreferences.getTimeZone();
                if (timeZone != null) {
                    use = timeZone.getUse();

                    offsetMinutes += timeZone.getOffsetHours() * 60;
                    offsetMinutes += timeZone.getOffsetMinutes();

                    zoneId = timeZone.getId();
                }

                pattern = userPreferences.getDateTimePattern();
                pattern = convertJavaDateTimePattern(pattern);
            }
        }

        return new TimeZoneSettings(use, pattern, offsetMinutes, zoneId);
    }

    /**
     * Strips the seconds, sub-seconds and zone from an already-converted
     * moment.js pattern, leaving everything that decides how the rest of it
     * reads — field order, separators, and whether the hour is 12- or 24-hour —
     * exactly as the user set it.
     *
     * <p>Examples: {@code YYYY-MM-DD[T]HH:mm:ss.SSSZ} becomes
     * {@code YYYY-MM-DD[T]HH:mm}, and {@code MM/DD/YYYY hh:mm:ss a} becomes
     * {@code MM/DD/YYYY hh:mm a}.</p>
     */
    static String dropSecondsAndZone(final String pattern) {
        String shortened = pattern;
        // Zone first, so that the seconds it usually follows are left at the end
        // of the pattern where the "not part of a longer run of letters" guard
        // below can see them. Taken with any space in front of it. Applied
        // before format() brackets a UTC "Z" into the literal "[Z]", so every Z
        // still standing here is the token rather than the suffix.
        shortened = shortened.replaceAll(" *Z+(?![A-Za-z])", "");
        shortened = shortened.replaceAll(" *z+(?![A-Za-z])", "");
        // Then sub-seconds, which would otherwise be left dangling by the
        // removal of the seconds they hang off. Each is taken with whatever
        // single character separates it from the field before — patterns are not
        // all colon-separated, and a stray separator reads as a typo.
        shortened = shortened.replaceAll("[^A-Za-z0-9]?S+(?![A-Za-z])", "");
        shortened = shortened.replaceAll("[^A-Za-z0-9]?ss(?![A-Za-z])", "");
        return shortened.trim();
    }

    String convertJavaDateTimePattern(final String pattern) {
        String converted = pattern;
        converted = converted.replace('y', 'Y');
        converted = converted.replace('d', 'D');
        converted = converted.replaceAll("'", "");
        converted = converted.replaceAll("SSSXX", "SSSZ");
        converted = converted.replaceAll("T", "[T]");
        converted = converted.replaceAll("xxx", "Z");
        converted = converted.replaceAll("xx", "z");
        converted = converted.replaceAll("VV", "ZZ");

        // Deal with day name formatting.
        converted = converted.replaceAll("E{2,}", "dddd");
        converted = converted.replaceAll("E", "ddd");
        converted = converted.replaceAll("e{4,}", "dddd");
        converted = converted.replaceAll("e{3,}", "ddd");
        converted = converted.replaceAll("e+", "d");
        converted = converted.replaceAll("c{4,}", "dddd");
        converted = converted.replaceAll("c{3,}", "ddd");
        converted = converted.replaceAll("c+", "d");


        return converted;
    }

    // ---------------------------------------------------------------

    private static final long ONE_SECOND = 1000;
    private static final long ONE_MINUTE = ONE_SECOND * 60;
    private static final long ONE_HOUR = ONE_MINUTE * 60;

    private static class TimeZoneSettings {

        private final Use use;
        private final String pattern;
        private final int offsetMinutes;
        private final String zoneId;

        private TimeZoneSettings(final Use use,
                                 final String pattern,
                                 final int offsetMinutes,
                                 final String zoneId) {
            this.use = use;
            this.pattern = pattern;
            this.offsetMinutes = offsetMinutes;
            this.zoneId = zoneId;
        }
    }
}
