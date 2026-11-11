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

package stroom.dependency.client.layout;

/// What a laid-out [Instance] draws.
public enum InstanceKind {

    /// A node on a lane's flow row.
    FLOW,

    /// One copy of a referenced document, in the reference row of a lane that uses it.
    REFERENCE,

    /// One copy of an endpoint outside Stroom, beneath the instance that calls it.
    ENDPOINT,

    /// A whole lane folded into one node.
    LANE_SUMMARY,

    /// A lane's reference row folded into one node.
    REFERENCE_SUMMARY
}
