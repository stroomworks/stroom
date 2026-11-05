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

import stroom.egress.shared.EgressAttemptDto;
import stroom.egress.shared.EgressClearRequest;
import stroom.egress.shared.EgressResource;
import stroom.egress.shared.EgressRuleDto;
import stroom.egress.shared.EgressSettingsDto;
import stroom.egress.shared.EgressTestRequest;
import stroom.egress.shared.EgressTestResponse;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.function.Supplier;

/// Implements the administrative interface. Every method needs the Manage Egress Policy permission, and every call is
/// audited by `@AutoLogged`.
@AutoLogged
public class EgressResourceImpl implements EgressResource {

    private final Provider<EgressService> serviceProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    EgressResourceImpl(final Provider<EgressService> serviceProvider,
                       final Provider<SecurityContext> securityContextProvider) {
        this.serviceProvider = serviceProvider;
        this.securityContextProvider = securityContextProvider;
    }

    @Override
    public List<EgressRuleDto> listRules() {
        return secure(() -> serviceProvider.get().listRules());
    }

    @Override
    public EgressRuleDto saveRule(final EgressRuleDto rule) {
        return secure(() -> {
            try {
                return serviceProvider.get().saveRule(rule);
            } catch (final IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage(), e);
            }
        });
    }

    @Override
    public Boolean deleteRule(final Long id) {
        return secure(() -> serviceProvider.get().deleteRule(id));
    }

    @Override
    public EgressSettingsDto getSettings() {
        return secure(() -> serviceProvider.get().getSettings());
    }

    @Override
    public EgressSettingsDto saveSettings(final EgressSettingsDto settings) {
        return secure(() -> serviceProvider.get().saveSettings(settings));
    }

    @Override
    public EgressTestResponse test(final EgressTestRequest request) {
        return secure(() -> serviceProvider.get().test(request));
    }

    @Override
    public List<EgressAttemptDto> listAttempts() {
        return secure(() -> serviceProvider.get().listAttempts());
    }

    @Override
    public String revealAttempt(final String id) {
        return secure(() -> serviceProvider.get().revealAttempt(id));
    }

    @Override
    public Integer clearAttempts(final EgressClearRequest request) {
        return secure(() -> serviceProvider.get().clearAttempts(request == null
                ? null
                : request.getBeforeMs()));
    }

    private <T> T secure(final Supplier<T> supplier) {
        return securityContextProvider.get().secureResult(AppPermission.MANAGE_EGRESS_POLICY, supplier);
    }
}
