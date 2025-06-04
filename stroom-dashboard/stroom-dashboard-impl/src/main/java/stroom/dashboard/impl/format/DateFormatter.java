/*
 * Copyright 2017 Crown Copyright
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

package stroom.dashboard.impl.format;

import stroom.query.api.DateTimeFormatSettings;
import stroom.query.api.FormatSettings;
import stroom.query.api.UserTimeZone;
import stroom.query.api.UserTimeZone.Use;
import stroom.query.language.functions.Val;
import stroom.util.date.DateFormatterCache;
import stroom.util.date.DateUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class DateFormatter implements Formatter {

    private final DateTimeFormatter format;

    private DateFormatter(final DateTimeFormatter format) {
        this.format = format;
    }

    public static DateFormatter create(final FormatSettings settings, final String dateTimeLocale) {
        Use use = Use.UTC;
        String pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS";
        int offsetHours = 0;
        int offsetMinutes = 0;
        String zoneId = "UTC";

        if (settings instanceof DateTimeFormatSettings) {
            final DateTimeFormatSettings dateTimeFormatSettings = (DateTimeFormatSettings) settings;
            if (dateTimeFormatSettings.getPattern() != null
                    && dateTimeFormatSettings.getPattern().trim().length() > 0) {
                pattern = dateTimeFormatSettings.getPattern();

                final UserTimeZone timeZone = dateTimeFormatSettings.getTimeZone();
                if (timeZone != null) {
                    if (timeZone.getUse() != null) {
                        use = timeZone.getUse();
                    }

                    offsetHours = getInt(timeZone.getOffsetHours());
                    offsetMinutes = getInt(timeZone.getOffsetMinutes());
                    zoneId = timeZone.getId();
                }
            }
        }

        ZoneId zone = ZoneOffset.UTC;
        if (UserTimeZone.Use.UTC.equals(use)) {
            zone = ZoneOffset.UTC;
        } else if (UserTimeZone.Use.LOCAL.equals(use)) {
            zone = ZoneId.systemDefault();

            try {
                if (dateTimeLocale != null) {
                    zone = ZoneId.of(dateTimeLocale);
                }
            } catch (final IllegalArgumentException e) {
                // The client time zone was not recognised so we'll
                // use the default.
            }

        } else if (UserTimeZone.Use.ID.equals(use)) {
            zone = ZoneId.of(zoneId);
        } else if (UserTimeZone.Use.OFFSET.equals(use)) {
            zone = ZoneOffset.ofHoursMinutes(offsetHours, offsetMinutes);
        }

        final DateTimeFormatter format = DateFormatterCache.getFormatter(pattern);
        return new DateFormatter(format);
    }

    private static int getInt(final Integer i) {
        if (i == null) {
            return 0;
        }
        return i;
    }

    @Override
    public String format(final Val value) {
        if (value == null) {
            return null;
        }

        final Long millis = value.toLong();
        if (millis != null) {
            if (format == null) {
                return DateUtil.createNormalDateTimeString(millis);
            }

            return format.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC));
        }
        return value.toString();
    }
}
