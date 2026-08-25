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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDateTimeFormatter {
    @Test
    void test() {
        final DateTimeFormatter dateTimeFormatter = new DateTimeFormatter(null);
        assertThat(dateTimeFormatter
                .convertJavaDateTimePattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX"))
                .isEqualTo("YYYY-MM-DD[T]HH:mm:ss.SSSZ");
        assertThat(dateTimeFormatter
                .convertJavaDateTimePattern("yyyy-MM-dd'T'HH'#'mm'#'ss,SSSXX"))
                .isEqualTo("YYYY-MM-DD[T]HH#mm#ss,SSSZ");
        assertThat(dateTimeFormatter
                .convertJavaDateTimePattern("E, dd MMM yyyy HH:mm:ss Z"))
                .isEqualTo("ddd, DD MMM YYYY HH:mm:ss Z");
    }

    /**
     * The short form drops the second, any sub-second digits and the zone, and
     * changes nothing else: field order, separators and the 12/24-hour choice
     * all still read as whatever the user asked for.
     */
    @Test
    void dropsSecondsAndZoneAndNothingElse() {
        assertThat(DateTimeFormatter.dropSecondsAndZone("YYYY-MM-DD[T]HH:mm:ss.SSSZ"))
                .isEqualTo("YYYY-MM-DD[T]HH:mm");
        // A pattern separated by something other than a colon keeps its own
        // separators, and loses them only along with the fields they introduce.
        assertThat(DateTimeFormatter.dropSecondsAndZone("YYYY-MM-DD[T]HH#mm#ss,SSSZ"))
                .isEqualTo("YYYY-MM-DD[T]HH#mm");
        // 12-hour with a meridiem: the "a" is not a zone and must survive.
        assertThat(DateTimeFormatter.dropSecondsAndZone("MM/DD/YYYY hh:mm:ss a"))
                .isEqualTo("MM/DD/YYYY hh:mm a");
        assertThat(DateTimeFormatter.dropSecondsAndZone("ddd, DD MMM YYYY HH:mm:ss Z"))
                .isEqualTo("ddd, DD MMM YYYY HH:mm");
        // Already short — nothing to take away.
        assertThat(DateTimeFormatter.dropSecondsAndZone("YYYY-MM-DD HH:mm"))
                .isEqualTo("YYYY-MM-DD HH:mm");
        // The day name ends in a run of letters that must not be mistaken for a
        // zone, and MMM must not be mistaken for a seconds field.
        assertThat(DateTimeFormatter.dropSecondsAndZone("dddd DD MMM YYYY"))
                .isEqualTo("dddd DD MMM YYYY");
    }
}
