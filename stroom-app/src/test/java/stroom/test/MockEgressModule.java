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

package stroom.test;

import stroom.egress.api.EgressDecisionRecorder;
import stroom.egress.api.EgressGuard;
import stroom.egress.api.EgressRuleSource;
import stroom.egress.api.EgressSettings;
import stroom.egress.impl.AggregatingDecisionRecorder;
import stroom.egress.impl.EgressGuardImpl;
import stroom.egress.impl.EgressStore;
import stroom.egress.impl.InMemoryEgressRegistry;

import com.google.inject.AbstractModule;

/// The egress policy for the integration tests that do not use the database: the real guard and the real recorder,
/// reading rules, modes and kill switches from memory. Every kind starts in report-only mode with no rules, so a test
/// that does not set a policy sees nothing refused.
public class MockEgressModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(EgressGuard.class).to(EgressGuardImpl.class);
        bind(EgressDecisionRecorder.class).to(AggregatingDecisionRecorder.class);
        bind(EgressSettings.class).to(InMemoryEgressRegistry.class);
        bind(EgressRuleSource.class).to(InMemoryEgressRegistry.class);
        bind(EgressStore.class).to(InMemoryEgressRegistry.class);
    }
}
