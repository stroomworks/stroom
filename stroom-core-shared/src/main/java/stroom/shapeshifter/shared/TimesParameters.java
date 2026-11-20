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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// The window an event's time must fall in to be plausible: so long before the stream was received, and so
/// long after it.
///
/// A timestamp with no year is the fault this catches. Read without one it lands in the wrong year — most
/// often the current one, which for a back-loaded stream can be years out — and nothing else notices,
/// because an event dated three years early still validates, still carries real content and still obeys
/// every business rule.
///
/// **The window is wide on purpose.** A year before covers a stream being back-loaded, which is ordinary;
/// a day after covers a clock that is a little ahead and a stream that arrives slowly. A whole-year error
/// is the clear case and this catches it. A time-zone error is only hours out, and no window tight enough
/// to catch that would survive back-loaded data — for zones the prompt's rule, and the `timeZone` property
/// of decision N6, are the tools.
@JsonPropertyOrder({"beforeDays", "afterDays"})
@JsonInclude(Include.NON_NULL)
public final class TimesParameters extends ScorerParameters {

    /// A year: a stream being loaded from an archive is ordinary, and a translation should not be refused
    /// for it.
    private static final int DEFAULT_BEFORE_DAYS = 365;
    /// A day: enough for a clock that is slightly ahead, or a stream that took a while to arrive.
    private static final int DEFAULT_AFTER_DAYS = 1;

    @JsonProperty
    private final int beforeDays;
    @JsonProperty
    private final int afterDays;

    /// @param beforeDays How long before the stream was received an event may be dated; the default is a
    ///                   year.
    /// @param afterDays  How long after it an event may be dated; the default is a day.
    @JsonCreator
    public TimesParameters(@JsonProperty("beforeDays") final Integer beforeDays,
                           @JsonProperty("afterDays") final Integer afterDays) {
        this.beforeDays = Objects.requireNonNullElse(beforeDays, DEFAULT_BEFORE_DAYS);
        this.afterDays = Objects.requireNonNullElse(afterDays, DEFAULT_AFTER_DAYS);
        if (this.beforeDays < 0 || this.afterDays < 0) {
            throw new IllegalArgumentException("A time window cannot be negative");
        }
    }

    @Override
    public Check check() {
        return Check.TIMES;
    }

    public int getBeforeDays() {
        return beforeDays;
    }

    public int getAfterDays() {
        return afterDays;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TimesParameters that = (TimesParameters) o;
        return beforeDays == that.beforeDays && afterDays == that.afterDays;
    }

    @Override
    public int hashCode() {
        return Objects.hash(beforeDays, afterDays);
    }

    @Override
    public String toString() {
        return "within " + beforeDays + " days before and " + afterDays + " days after the stream";
    }
}
