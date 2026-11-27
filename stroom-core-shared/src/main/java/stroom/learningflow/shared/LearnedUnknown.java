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

package stroom.learningflow.shared;

/// Defines the marker that identifies a deliberately learned Unknown event.
///
/// When the model cannot translate a kind of record, the fallback (exhaustion) step asks it to write an
/// `Unknown` event carrying a `Data` item named [#MARKER] inside `EventDetail/Unknown`, whose value is the
/// model's name for that record type. The marker is defined once, here, so that the prompt text that asks for
/// it, the scorer that accepts it and anything downstream that reads it all agree. An Unknown without the
/// marker is a bare Unknown, which the extraction quality scorer refuses.
public final class LearnedUnknown {

    /// The `Name` of the marker `Data` item.
    public static final String MARKER = "LearningFlowUnknown";

    private LearnedUnknown() {
    }

    /// The admitted form for one record type, as the exhaustion step asks the model to write it.
    ///
    /// @param recordType The model's name for the record type.
    public static String markup(final String recordType) {
        return "<Unknown><Data Name=\"" + MARKER + "\" Value=\"" + recordType + "\"/></Unknown>";
    }
}
