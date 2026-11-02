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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.shared.LearnedUnknown;

import net.sf.saxon.s9api.XdmNode;

/// The admitted form as the scorers see it (A53, A62): an `EventDetail/Unknown` carrying the marker of
/// [LearnedUnknown] is a learned admission and clears the bar; one without it is a bare Unknown and does
/// not. The marker item itself is left out of any count of untyped elements.
public final class Admission {

    /// The marker `Data` item, wherever it is.
    private static final String MARKER_ITEM = "*[local-name() = 'Data'][@Name = '" + LearnedUnknown.MARKER + "']";
    /// A record's `EventDetail/Unknown` that carries the marker.
    public static final String MARKED_UNKNOWN = "*[local-name() = 'EventDetail']/*[local-name() = 'Unknown']["
                                                + MARKER_ITEM + "]";
    /// A record's `EventDetail/Unknown` without the marker: refused as it always was.
    public static final String BARE_UNKNOWN = "*[local-name() = 'EventDetail']/*[local-name() = 'Unknown'][not("
                                              + MARKER_ITEM + ")]";
    /// Every `Data` element but the marker.
    public static final String DATA_BUT_MARKER = "descendant-or-self::*[local-name() = 'Data'][not(@Name = '"
                                                 + LearnedUnknown.MARKER + "')]";

    private Admission() {
    }

    /// Whether a record of the output is a learned admission.
    public static boolean admits(final OutputRecords output, final XdmNode record) {
        return output.holds(record, MARKED_UNKNOWN);
    }

    /// Whether any record of the output is a learned admission.
    public static boolean admitsAny(final OutputRecords output) {
        return output.records().stream().anyMatch(record -> admits(output, record));
    }
}
