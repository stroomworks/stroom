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

package stroom.util.shared.time;

import stroom.docref.HasDisplayValue;
import stroom.util.shared.HasPrimitiveValue;
import stroom.util.shared.PrimitiveValueConverter;

public enum TimeUnit implements HasDisplayValue, HasPrimitiveValue {
    NANOSECONDS("Nanoseconds", 0, "ns"),
    MILLISECONDS("Milliseconds", 1, "ms"),
    SECONDS("Seconds", 2, "s"),
    MINUTES("Minutes", 3, "m"),
    HOURS("Hours", 4, "h"),
    DAYS("Days", 5, "d"),
    WEEKS("Weeks", 6, "w"),
    MONTHS("Months", 7, "M"),
    YEARS("Years", 8, "y");

    public static final PrimitiveValueConverter<TimeUnit> PRIMITIVE_VALUE_CONVERTER =
            PrimitiveValueConverter.create(TimeUnit.class, TimeUnit.values());

    private final String displayValue;
    private final byte primitiveValue;
    private final String shortForm;

    TimeUnit(final String displayValue,
             final int primitiveValue,
             final String shortForm) {
        this.displayValue = displayValue;
        this.primitiveValue = (byte) primitiveValue;
        this.shortForm = shortForm;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    @Override
    public byte getPrimitiveValue() {
        return primitiveValue;
    }

    public String getShortForm() {
        return shortForm;
    }
}
