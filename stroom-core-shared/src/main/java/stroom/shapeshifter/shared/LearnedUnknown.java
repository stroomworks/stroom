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

package stroom.shapeshifter.shared;

/// The marker of a learned Unknown (A53, A62, A74): a `Data` item named [#MARKER] inside
/// `EventDetail/Unknown`, whose value is the model's name for the record type it admitted it could not
/// translate. Defined once, here, so that the text that asks for it, the scorer that accepts it and
/// anything downstream that reads it cannot drift apart. An Unknown without it is a bare Unknown, which
/// the quality scorer refuses as it always did.
public final class LearnedUnknown {

    /// The `Name` of the marker `Data` item.
    public static final String MARKER = "ShapeshifterUnknown";

    private LearnedUnknown() {
    }

    /// The admitted form for one record type, as the exhaustion step asks the model to write it.
    ///
    /// @param recordType The model's name for the record type.
    public static String markup(final String recordType) {
        return "<Unknown><Data Name=\"" + MARKER + "\" Value=\"" + recordType + "\"/></Unknown>";
    }
}
