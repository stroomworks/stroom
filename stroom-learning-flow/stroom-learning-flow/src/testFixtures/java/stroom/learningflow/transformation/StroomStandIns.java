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

package stroom.learningflow.transformation;

import stroom.util.date.DateUtil;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/// Stand-ins for the `stroom:` functions a learned stylesheet may use, so that the scenario harness runs a
/// stylesheet the same way a node would.
///
/// The harness drives Saxon directly, without Stroom's function library, so without these a stylesheet
/// calling `stroom:format-date` would fail here and succeed on a node: the harness would refuse what the
/// [allow-list][AiXsltAllowList] permits. These close that gap for the functions they cover.
///
/// **Not every function has a stand-in.** [#STOOD_IN] is smaller than the allow-list's `stroom:` set: the
/// hash, IP, URL and entropy functions have no stand-in, so a stylesheet using one passes the static check
/// and then fails in the harness. [#missing(AiXsltAllowList)] names them, and a test checks that list
/// against what is expected, so that it does not change unnoticed.
///
/// **These are stand-ins, not the real functions.** `format-date` here reproduces the behaviour of
/// Stroom's version (the three argument forms, the reference time for a partial date, and the standard
/// output format, which it takes from the real [DateUtil]) but not its leniency or its week-based parsing.
/// `TestJudgingTakesTheYearFromTheStream` in `stroom-app` checks that this stand-in and the real function
/// fill in a missing year the same way.
public final class StroomStandIns {

    /// The `stroom:` functions these stand-ins cover.
    public static final Set<String> STOOD_IN = Set.of(
            "format-date", "meta", "feed-name", "stream-id", "source-id", "part-no", "record-no",
            "pipeline-name");

    private static final Locale LOCALE = Locale.ENGLISH;

    private StroomStandIns() {
    }

    /// Returns the `stroom:` functions on the allow-list that have no stand-in, so that a stylesheet using
    /// one passes the static check and then fails in the harness.
    ///
    /// @param allowList The allow-list to compare against.
    /// @return Their names, sorted; empty if the harness covers the whole list.
    public static List<String> missing(final AiXsltAllowList allowList) {
        final List<String> missing = new ArrayList<>();
        for (final String name : HardCodedXsltAllowList.stroomFunctions()) {
            if (!STOOD_IN.contains(name) && allowList.allowsFunction(AiXsltAllowList.STROOM_NAMESPACE, name)) {
                missing.add(name);
            }
        }
        missing.sort(String::compareTo);
        return missing;
    }

    /// Returns the stand-ins, for Saxon to register.
    ///
    /// @param facts The stream being translated, which is the only thing they may read.
    /// @return One definition per function of [#STOOD_IN].
    public static List<ExtensionFunctionDefinition> definitions(final StreamFacts facts) {
        return List.of(
                new StandIn("format-date", 1, 5, arguments -> formatDate(facts, arguments)),
                new StandIn("meta", 1, 1, arguments -> facts.attribute(arguments[0])),
                new StandIn("feed-name", 0, 0, arguments -> facts.feed()),
                new StandIn("pipeline-name", 0, 0, arguments -> "harness"),
                new StandIn("stream-id", 0, 0, arguments -> String.valueOf(facts.id())),
                new StandIn("source-id", 0, 0, arguments -> String.valueOf(facts.id())),
                new StandIn("part-no", 0, 0, arguments -> "1"),
                new StandIn("record-no", 0, 0, arguments -> "1"));
    }

    /// Stroom's `format-date`, in its three forms: milliseconds alone; a value and the pattern that reads
    /// it, with an optional zone; and the same with a pattern and zone to write it in. A field the pattern
    /// does not read (most often the year) is taken from when the stream was received, never from the
    /// clock.
    private static String formatDate(final StreamFacts facts, final String[] arguments) {
        final String value = arguments[0];
        if (value == null || value.isBlank()) {
            return null;
        }
        if (arguments.length == 1) {
            return DateUtil.createNormalDateTimeString(Long.parseLong(value.trim()));
        }
        final String patternIn = arguments[1];
        final boolean specifiedOut = arguments.length >= 4;
        final String zoneIn = specifiedOut
                ? arguments[2]
                : (arguments.length == 3
                        ? arguments[2]
                        : null);
        final long ms = parse(facts, value.trim(), patternIn, zoneIn);
        if (!specifiedOut) {
            return DateUtil.createNormalDateTimeString(ms);
        }
        final String patternOut = arguments[3];
        final ZoneId zoneOut = zone(arguments.length == 5
                ? arguments[4]
                : null);
        return DateTimeFormatter.ofPattern(patternOut, LOCALE)
                .format(java.time.Instant.ofEpochMilli(ms).atZone(zoneOut));
    }

    private static long parse(final StreamFacts facts,
                              final String value,
                              final String pattern,
                              final String timeZone) {
        final ZoneId zoneId = zone(timeZone);
        final DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder()
                .parseLenient()
                .parseCaseInsensitive()
                .appendPattern(pattern);
        // Fields the pattern does not read are taken from the stream's received time, so that a syslog
        // date without a year means the same thing when it is judged and when it is served.
        final DateTimeFormatter probe = builder.toFormatter(LOCALE);
        final ZonedDateTime reference = java.time.Instant.ofEpochMilli(facts.requireReceivedMs())
                .atZone(zoneId);
        for (final ChronoField field : List.of(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR,
                ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR,
                ChronoField.SECOND_OF_MINUTE, ChronoField.MILLI_OF_SECOND)) {
            if (!reads(probe, value, field)) {
                builder.parseDefaulting(field, field == ChronoField.YEAR
                        ? reference.get(ChronoField.YEAR)
                        : defaultOf(field, reference));
            }
        }
        final TemporalAccessor parsed = builder.toFormatter(LOCALE).parse(value);
        return ZonedDateTime.of(
                parsed.get(ChronoField.YEAR),
                parsed.get(ChronoField.MONTH_OF_YEAR),
                parsed.get(ChronoField.DAY_OF_MONTH),
                parsed.get(ChronoField.HOUR_OF_DAY),
                parsed.get(ChronoField.MINUTE_OF_HOUR),
                parsed.get(ChronoField.SECOND_OF_MINUTE),
                parsed.get(ChronoField.MILLI_OF_SECOND) * 1_000_000,
                zoneId).toInstant().toEpochMilli();
    }

    private static long defaultOf(final ChronoField field, final ZonedDateTime reference) {
        return switch (field) {
            case MONTH_OF_YEAR, DAY_OF_MONTH -> field == ChronoField.MONTH_OF_YEAR
                    ? 1
                    : 1;
            default -> 0;
        };
    }

    /// @return Whether the pattern reads the field from this value, so that it must not be defaulted.
    private static boolean reads(final DateTimeFormatter formatter, final String value, final ChronoField field) {
        try {
            return formatter.parse(value).isSupported(field);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private static ZoneId zone(final String timeZone) {
        return timeZone == null || timeZone.isBlank()
                ? ZoneOffset.UTC
                : ZoneId.of(timeZone.trim());
    }


    // --------------------------------------------------------------------------------


    /// One stand-in function, in the form Saxon registers: a name in the `stroom` namespace, how many
    /// string arguments it takes, and what it returns.
    private static final class StandIn extends ExtensionFunctionDefinition {

        private final StructuredQName name;
        private final int minArgs;
        private final int maxArgs;
        private final transient Function<String[], String> body;

        private StandIn(final String localName,
                        final int minArgs,
                        final int maxArgs,
                        final Function<String[], String> body) {
            this.name = new StructuredQName("", AiXsltAllowList.STROOM_NAMESPACE, localName);
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.body = body;
        }

        @Override
        public StructuredQName getFunctionQName() {
            return name;
        }

        @Override
        public int getMinimumNumberOfArguments() {
            return minArgs;
        }

        @Override
        public int getMaximumNumberOfArguments() {
            return maxArgs;
        }

        @Override
        public SequenceType[] getArgumentTypes() {
            final SequenceType[] types = new SequenceType[Math.max(maxArgs, 1)];
            for (int i = 0; i < types.length; i++) {
                types[i] = SequenceType.OPTIONAL_STRING;
            }
            return types;
        }

        @Override
        public SequenceType getResultType(final SequenceType[] suppliedArgumentTypes) {
            return SequenceType.OPTIONAL_STRING;
        }

        @Override
        public ExtensionFunctionCall makeCallExpression() {
            return new ExtensionFunctionCall() {
                @Override
                public Sequence call(final XPathContext context, final Sequence[] arguments)
                        throws XPathException {
                    final String[] values = new String[arguments.length];
                    for (int i = 0; i < arguments.length; i++) {
                        final var item = arguments[i].head();
                        values[i] = item == null
                                ? null
                                : item.getStringValue();
                    }
                    try {
                        final String result = body.apply(values);
                        return result == null
                                ? net.sf.saxon.value.EmptySequence.getInstance()
                                : new StringValue(result);
                    } catch (final RuntimeException e) {
                        throw new XPathException("stroom:" + name.getLocalPart() + " failed: "
                                                 + e.getMessage(), e);
                    }
                }
            };
        }
    }
}
