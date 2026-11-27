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

package stroom.learningflow.scoring;

import stroom.learningflow.shared.LearnedUnknown;

import net.sf.saxon.s9api.XdmNode;

/// XPath expressions and helpers that let the scorers recognise a learned Unknown event. When the model
/// cannot translate a kind of record, the exhaustion step has it write an `EventDetail/Unknown` carrying
/// the [LearnedUnknown] marker. Such a record is a learned admission and clears the bar; an Unknown
/// without the marker is a bare Unknown and does not. The marker item itself is left out of any count of
/// untyped `Data` elements.
public final class Admission {

    /// Matches the marker `Data` item.
    private static final String MARKER_ITEM = "*[local-name() = 'Data'][@Name = '" + LearnedUnknown.MARKER + "']";
    /// Matches a record's `EventDetail/Unknown` that carries the marker.
    public static final String MARKED_UNKNOWN = "*[local-name() = 'EventDetail']/*[local-name() = 'Unknown']["
                                                + MARKER_ITEM + "]";
    /// Matches a record's `EventDetail/Unknown` without the marker, which is refused.
    public static final String BARE_UNKNOWN = "*[local-name() = 'EventDetail']/*[local-name() = 'Unknown'][not("
                                              + MARKER_ITEM + ")]";
    /// Matches every `Data` element except the marker.
    public static final String DATA_BUT_MARKER = "descendant-or-self::*[local-name() = 'Data'][not(@Name = '"
                                                 + LearnedUnknown.MARKER + "')]";

    private Admission() {
    }

    /// Whether a record of the output is a learned admission, i.e. a marked Unknown.
    public static boolean admits(final OutputRecords output, final XdmNode record) {
        return output.holds(record, MARKED_UNKNOWN);
    }

    /// Whether any record of the output is a learned admission.
    public static boolean admitsAny(final OutputRecords output) {
        return output.records().stream().anyMatch(record -> admits(output, record));
    }
}
