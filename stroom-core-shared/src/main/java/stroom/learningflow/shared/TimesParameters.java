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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// Parameters for the event times check: the window an event's time must fall in to be plausible, as a number
/// of days before and after the stream was received.
///
/// The main fault this catches is a timestamp with no year. Read without one, it lands in the wrong year
/// (most often the current one, which for a back-loaded stream can be years out), and nothing else notices,
/// because an event dated three years early still validates, still carries real content and still obeys
/// every business rule.
///
/// **The window is wide on purpose.** A year before covers a stream being back-loaded, which is ordinary; a
/// day after covers a clock that is a little ahead and a stream that arrives slowly. A whole-year error is
/// the clear case and this catches it. A time-zone error is only hours out, and no window tight enough to
/// catch that would cope with back-loaded data; time zones are handled by the prompt's rule and the Smart
/// Pipeline element's `timeZone` property instead.
///
/// **The time judged** is event-logging's `EventTime/TimeCreated` unless the Learning Flow document names
/// another field. A schema that isn't event-logging keeps its time elsewhere, so where such a document names
/// no field the check doesn't run.
@JsonPropertyOrder({"beforeDays", "afterDays", "timeField"})
@JsonInclude(Include.NON_NULL)
public final class TimesParameters extends ScorerParameters {

    /// A year, because a stream being loaded from an archive is ordinary, and a translation should not be
    /// refused for it.
    private static final int DEFAULT_BEFORE_DAYS = 365;
    /// A day, which is enough for a clock that is slightly ahead, or a stream that took a while to arrive.
    private static final int DEFAULT_AFTER_DAYS = 1;

    @JsonProperty
    private final int beforeDays;
    @JsonProperty
    private final int afterDays;
    @JsonProperty
    private final String timeField;

    /// Creates a window that judges event-logging's time field.
    ///
    /// @param beforeDays How long before the stream was received an event may be dated; the default is a
    ///                   year.
    /// @param afterDays  How long after it an event may be dated; the default is a day.
    public TimesParameters(final Integer beforeDays, final Integer afterDays) {
        this(beforeDays, afterDays, null);
    }

    /// @param beforeDays How long before the stream was received an event may be dated; the default is a
    ///                   year.
    /// @param afterDays  How long after it an event may be dated; the default is a day.
    /// @param timeField  An XPath, from a record, to the time to judge, e.g. `EventTime/TimeCreated`; null
    ///                   or blank for event-logging's.
    @JsonCreator
    public TimesParameters(@JsonProperty("beforeDays") final Integer beforeDays,
                           @JsonProperty("afterDays") final Integer afterDays,
                           @JsonProperty("timeField") final String timeField) {
        this.beforeDays = Objects.requireNonNullElse(beforeDays, DEFAULT_BEFORE_DAYS);
        this.afterDays = Objects.requireNonNullElse(afterDays, DEFAULT_AFTER_DAYS);
        this.timeField = timeField == null || timeField.isBlank()
                ? null
                : timeField.trim();
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

    /// @return The XPath, from a record, to the time to judge; null for event-logging's
    ///         `EventTime/TimeCreated`.
    public String getTimeField() {
        return timeField;
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
        return beforeDays == that.beforeDays
               && afterDays == that.afterDays
               && Objects.equals(timeField, that.timeField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(beforeDays, afterDays, timeField);
    }

    @Override
    public String toString() {
        return (timeField == null
                ? ""
                : timeField + " ")
               + "within " + beforeDays + " days before and " + afterDays + " days after the stream";
    }
}
