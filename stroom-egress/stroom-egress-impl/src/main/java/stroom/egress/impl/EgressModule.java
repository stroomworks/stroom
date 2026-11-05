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

package stroom.egress.impl;

import stroom.egress.api.EgressDecisionRecorder;
import stroom.egress.api.EgressGuard;
import stroom.egress.api.EgressRuleSource;
import stroom.egress.api.EgressSettings;
import stroom.egress.shared.EgressResource;
import stroom.lifecycle.api.LifecycleBinder;
import stroom.util.RunnableWrapper;
import stroom.util.guice.RestResourcesBinder;
import stroom.util.xml.saxon.SaxonConfinement;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;

/// Binds the egress guard and installs it behind the Saxon hooks at start-up.
public class EgressModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(EgressGuard.class).to(EgressGuardImpl.class);
        bind(EgressDecisionRecorder.class).to(AggregatingDecisionRecorder.class);
        bind(EgressDao.class).to(EgressDaoImpl.class);
        bind(EgressSettings.class).to(DbEgressRegistry.class);
        bind(EgressStore.class).to(DbEgressRegistry.class);
        bind(EgressResource.class).to(EgressResourceImpl.class);
        RestResourcesBinder.create(binder()).bind(EgressResourceImpl.class);
        bind(EgressRuleSource.class).to(DbEgressRegistry.class);

        // Early, so that every Saxon configuration created afterwards asks the policy.
        LifecycleBinder.create(binder())
                .bindStartupTaskTo(InstallSaxonGuardStartup.class, 5)
                // Before the Saxon guard, which reads the policy as soon as a stylesheet asks for a document.
                .bindStartupTaskTo(LoadPolicyStartup.class, 6)
                .bindShutdownTaskTo(StopPolicyReloadShutdown.class);
    }


    // --------------------------------------------------------------------------------


    private static class LoadPolicyStartup extends RunnableWrapper {

        @Inject
        LoadPolicyStartup(final DbEgressRegistry registry) {
            super(registry::start);
        }
    }

    private static class StopPolicyReloadShutdown extends RunnableWrapper {

        @Inject
        StopPolicyReloadShutdown(final DbEgressRegistry registry) {
            super(registry::stop);
        }
    }

    private static class InstallSaxonGuardStartup extends RunnableWrapper {

        @Inject
        InstallSaxonGuardStartup(final SaxonEgressAccessGuard saxonGuard) {
            super(() -> SaxonConfinement.install(saxonGuard));
        }
    }
}
