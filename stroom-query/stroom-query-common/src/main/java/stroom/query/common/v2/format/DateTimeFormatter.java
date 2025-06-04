/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.common.v2.format;

import stroom.query.api.DateTimeFormatSettings;
import stroom.query.api.DateTimeSettings;
import stroom.query.api.UserTimeZone;
import stroom.query.language.functions.DateUtil;
import stroom.query.language.functions.Val;
import stroom.util.shared.NullSafe;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public class DateTimeFormatter implements Formatter {

    private final java.time.format.DateTimeFormatter format;
    private final ZoneId zone;

    private DateTimeFormatter(final java.time.format.DateTimeFormatter format,
                              final ZoneId zone) {
        this.format = format;
        this.zone = zone;
    }

    public static DateTimeFormatter create(final DateTimeFormatSettings dateTimeFormat,
                                           final DateTimeSettings defaultDateTimeSettings) {
        java.time.format.DateTimeFormatter format = null;
        ZoneId zone = ZoneOffset.UTC;

        String pattern = null;
        UserTimeZone timeZone = null;
        if (dateTimeFormat != null && !dateTimeFormat.isUsePreferences()) {
            pattern = dateTimeFormat.getPattern();
            timeZone = dateTimeFormat.getTimeZone();
        } else if (defaultDateTimeSettings != null) {
            pattern = defaultDateTimeSettings.getDateTimePattern();
            timeZone = defaultDateTimeSettings.getTimeZone();
        }

        if (!NullSafe.isBlankString(pattern)) {
            if (timeZone != null) {
                if (UserTimeZone.Use.UTC.equals(timeZone.getUse())) {
                    zone = ZoneOffset.UTC;
                } else if (UserTimeZone.Use.LOCAL.equals(timeZone.getUse())) {
                    zone = ZoneId.systemDefault();

                    try {
                        if (defaultDateTimeSettings != null) {
                            zone = ZoneId.of(defaultDateTimeSettings.getLocalZoneId());
                        }
                    } catch (final IllegalArgumentException e) {
                        // The client time zone was not recognised so we'll
                        // use the default.
                    }

                } else if (UserTimeZone.Use.ID.equals(timeZone.getUse())) {
                    zone = ZoneId.of(timeZone.getId());
                } else if (UserTimeZone.Use.OFFSET.equals(timeZone.getUse())) {
                    zone = ZoneOffset.ofHoursMinutes(
                            NullSafe.getInt(timeZone.getOffsetHours()),
                            NullSafe.getInt(timeZone.getOffsetMinutes()));
                }
            }

            format = java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
        }

        return new DateTimeFormatter(format, zone);
    }

    public LocalDateTime parse(final String value) throws DateTimeParseException {
        return NullSafe.get(
                value,
                val -> DateUtil.parseLocal(val, format, zone));
    }

    @Override
    public String format(final Val value) {
        if (value == null) {
            return null;
        } else {
            final Long millis = value.toLong();
            if (millis != null) {
                if (format == null) {
                    return DateUtil.createNormalDateTimeString(millis);
                } else {
                    return format.format(Instant.ofEpochMilli(millis).atZone(zone));
                }
            } else {
                return value.toString();
            }
        }
    }
}
