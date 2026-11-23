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

/// Where the time zone a Smart Pipeline reads timestamps in came from (decision N6), which its Learning Status
/// tab shows so that a surprising date can be traced to what set the zone (Track 2 step 4).
public enum TimeZoneSource {
    /// The stream's own `TimeZone` header: the sender said so.
    HEADER,
    /// The Smart Pipeline element's `timeZone` property.
    PIPELINE,
    /// Neither was set, so times with no offset are read as UTC.
    DEFAULT
}
