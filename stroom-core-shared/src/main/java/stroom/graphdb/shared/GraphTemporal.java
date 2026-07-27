/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.graphdb.shared;

/**
 * GWT-safe helpers for the Graph DB Data tab's temporal "time-travel" slider (Cytoscape UI extension).
 *
 * <p>The slider re-runs the displayed query at successive instants by rewriting its temporal clause to
 * {@code AS OF datetime('<iso>')} and running it through the normal search path (no new backend endpoint - the
 * engine already honours {@code AS OF}; see {@code AstAsOf}). Client-side Cypher parsing is not GWT-compilable
 * (the ANTLR runtime is not GWT-safe), so {@link #withAsOf} does a small, reserved-word-anchored text rewrite
 * instead. All logic here is pure (no {@code java.time}, {@code java.util.regex} or {@code SimpleDateFormat},
 * none of which are GWT-safe) and unit-tested in {@code TestGraphTemporal}.</p>
 *
 * <p><b>Rewrite contract.</b> A temporal clause sits between the {@code MATCH} pattern and the first of
 * {@code WHERE} / {@code WITH} / {@code RETURN} (grammar: {@code MATCH pattern temporalClause? whereClause?}).
 * {@link #withAsOf} strips any existing clause of the four forms ({@code AS OF}, {@code AROUND}, {@code BETWEEN},
 * {@code DIFF}) and inserts {@code AS OF datetime('<iso>')} at that anchor. String literals are skipped so a
 * keyword inside a quoted value is never mistaken for a clause. A query with no {@code RETURN} is returned
 * unchanged (nowhere safe to place the clause); if the rewrite ever produces invalid Cypher the existing query
 * error surface reports it - it can never silently corrupt a result.</p>
 */
public final class GraphTemporal {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private GraphTemporal() {
    }

    // ---- query rewriting ----

    /**
     * Rewrite {@code query} so it runs as of {@code instantMillis} (UTC), replacing any existing temporal clause.
     *
     * @return the rewritten query, or the original unchanged if it has no {@code RETURN} to anchor against.
     */
    public static String withAsOf(final String query, final long instantMillis) {
        return withTemporalClause(query, "AS OF datetime('" + formatIsoUtc(instantMillis) + "')");
    }

    /**
     * Rewrite {@code query} to compare two instants - {@code DIFF FROM datetime(from) TO datetime(to)} - replacing
     * any existing temporal clause. The result's element rows carry a {@code changeKind} the graph view styles as
     * added / removed / modified / unchanged.
     *
     * @return the rewritten query, or the original unchanged if it has no {@code RETURN} to anchor against.
     */
    public static String withDiff(final String query, final long fromMillis, final long toMillis) {
        return withTemporalClause(query,
                "DIFF FROM datetime('" + formatIsoUtc(fromMillis) + "') TO datetime('" + formatIsoUtc(toMillis) + "')");
    }

    /**
     * Insert {@code clause} as the query's temporal clause (between the {@code MATCH} pattern and the first of
     * {@code WHERE} / {@code WITH} / {@code RETURN}), replacing any existing temporal clause.
     */
    private static String withTemporalClause(final String query, final String clause) {
        if (query == null) {
            return null;
        }
        final Scan scan = scan(query);
        if (scan.returnStart < 0) {
            return query;
        }

        int anchor = scan.returnStart;
        if (scan.whereStart >= 0 && scan.whereStart < anchor) {
            anchor = scan.whereStart;
        }
        if (scan.withStart >= 0 && scan.withStart < anchor) {
            anchor = scan.withStart;
        }

        // If an existing temporal clause precedes the anchor, strip it back to its start.
        int removeFrom = anchor;
        if (scan.temporalStart >= 0 && scan.temporalStart < anchor) {
            removeFrom = scan.temporalStart;
        }

        final String head = rtrim(query.substring(0, removeFrom));
        final String tail = ltrim(query.substring(anchor));
        return head + " " + clause + " " + tail;
    }

    /**
     * Left-to-right scan recording, at top level and outside string literals, the start offset of the first
     * temporal-clause keyword, the first {@code WHERE}, the first {@code WITH} and the first {@code RETURN}.
     * Scanning stops at {@code RETURN} (an {@code AS} alias in a return item must not be read as {@code AS OF}).
     */
    private static Scan scan(final String query) {
        final Scan scan = new Scan();
        final int n = query.length();
        int i = 0;
        String prevWord = null;
        int prevWordStart = -1;

        while (i < n) {
            final char c = query.charAt(i);

            // Skip a string literal (single or double quoted, backslash escapes honoured).
            if (c == '\'' || c == '"') {
                final char quote = c;
                i++;
                while (i < n) {
                    final char q = query.charAt(i);
                    if (q == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (q == quote) {
                        break;
                    }
                }
                prevWord = null;
                continue;
            }

            if (isWordChar(c)) {
                final int start = i;
                while (i < n && isWordChar(query.charAt(i))) {
                    i++;
                }
                final String word = query.substring(start, i).toUpperCase();

                if ("RETURN".equals(word)) {
                    scan.returnStart = start;
                    return scan;
                }
                if ("WHERE".equals(word) && scan.whereStart < 0) {
                    scan.whereStart = start;
                }
                if ("WITH".equals(word) && scan.withStart < 0) {
                    scan.withStart = start;
                }
                if (scan.temporalStart < 0) {
                    if ("AROUND".equals(word) || "BETWEEN".equals(word) || "DIFF".equals(word)) {
                        scan.temporalStart = start;
                    } else if ("OF".equals(word) && "AS".equals(prevWord)) {
                        scan.temporalStart = prevWordStart;
                    }
                }
                prevWord = word;
                prevWordStart = start;
                continue;
            }

            if (!isWhitespace(c)) {
                prevWord = null;
            }
            i++;
        }
        return scan;
    }

    private static final class Scan {

        private int temporalStart = -1;
        private int whereStart = -1;
        private int withStart = -1;
        private int returnStart = -1;
    }

    private static boolean isWordChar(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isWhitespace(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static String rtrim(final String s) {
        int end = s.length();
        while (end > 0 && isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String ltrim(final String s) {
        int start = 0;
        while (start < s.length() && isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    // ---- ISO-8601 UTC <-> epoch millis (pure arithmetic; no java.time / SimpleDateFormat) ----

    /**
     * Format epoch millis as {@code yyyy-MM-dd'T'HH:mm:ss'Z'} (UTC). Sub-second precision is dropped - the
     * slider's granularity is seconds.
     */
    public static String formatIsoUtc(final long millis) {
        // Floor-divide into whole days + non-negative remainder (plain arithmetic, GWT-version-independent).
        long days = millis / MILLIS_PER_DAY;
        long millisOfDay = millis % MILLIS_PER_DAY;
        if (millisOfDay < 0) {
            millisOfDay += MILLIS_PER_DAY;
            days--;
        }
        int rem = (int) (millisOfDay / 1000L);
        final int second = rem % 60;
        rem /= 60;
        final int minute = rem % 60;
        final int hour = rem / 60;

        final int[] ymd = civilFromDays(days);
        return pad(ymd[0], 4) + "-" + pad(ymd[1], 2) + "-" + pad(ymd[2], 2)
                + "T" + pad(hour, 2) + ":" + pad(minute, 2) + ":" + pad(second, 2) + "Z";
    }

    /**
     * Parse an ISO-8601 UTC instant. Accepts a date ({@code yyyy-MM-dd}) or a date-time
     * ({@code yyyy-MM-ddTHH:mm[:ss]}) with an optional trailing {@code Z}; any fractional seconds or explicit
     * offset beyond the seconds field are ignored (treated as UTC).
     *
     * @throws IllegalArgumentException if the text is not a recognisable instant.
     */
    public static long parseIsoUtc(final String text) {
        if (text == null) {
            throw new IllegalArgumentException("null instant");
        }
        final String s = text.trim();
        try {
            final int year = Integer.parseInt(s.substring(0, 4));
            final int month = Integer.parseInt(s.substring(5, 7));
            final int day = Integer.parseInt(s.substring(8, 10));
            int hour = 0;
            int minute = 0;
            int second = 0;
            if (s.length() >= 16 && (s.charAt(10) == 'T' || s.charAt(10) == ' ')) {
                hour = Integer.parseInt(s.substring(11, 13));
                minute = Integer.parseInt(s.substring(14, 16));
                if (s.length() >= 19 && s.charAt(16) == ':') {
                    second = Integer.parseInt(s.substring(17, 19));
                }
            }
            if (month < 1 || month > 12 || day < 1 || day > 31
                    || hour > 23 || minute > 59 || second > 59) {
                throw new IllegalArgumentException("out-of-range field in instant: " + text);
            }
            final long days = daysFromCivil(year, month, day);
            return days * MILLIS_PER_DAY + ((hour * 60L + minute) * 60L + second) * 1000L;
        } catch (final IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("not an ISO-8601 instant: " + text, e);
        }
    }

    // Howard Hinnant's days_from_civil / civil_from_days (public-domain), proleptic Gregorian, UTC.
    private static long daysFromCivil(final int yIn, final int m, final int d) {
        final int y = yIn - (m <= 2 ? 1 : 0);
        final int era = (y >= 0 ? y : y - 399) / 400;
        final int yoe = y - era * 400;
        final int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        final int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146097 + doe - 719468;
    }

    private static int[] civilFromDays(final long zIn) {
        final long z = zIn + 719468;
        final int era = (int) ((z >= 0 ? z : z - 146096) / 146097);
        final int doe = (int) (z - (long) era * 146097);
        final int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        final int y = yoe + era * 400;
        final int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        final int mp = (5 * doy + 2) / 153;
        final int d = doy - (153 * mp + 2) / 5 + 1;
        final int m = mp + (mp < 10 ? 3 : -9);
        return new int[]{y + (m <= 2 ? 1 : 0), m, d};
    }

    private static String pad(final int value, final int width) {
        final String s = Integer.toString(value);
        if (s.length() >= width) {
            return s;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }
}
