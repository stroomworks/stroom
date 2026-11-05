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

package stroom.egress.api;

import stroom.egress.shared.EgressKind;

import java.util.List;

/// Where the guard reads its rules from. The implementation holds them in memory and refreshes them when they
/// change.
public interface EgressRuleSource {

    /// The rules of one kind that are in force.
    ///
    /// @param kind The kind.
    /// @return The enabled rules.
    /// @throws RuntimeException If the registry is unavailable. The guard treats that as a failure.
    List<EgressRule> getEnabledRules(EgressKind kind);
}
