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

import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressRuleDto;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.util.shared.PermissionException;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestEgressResourceImpl {

    private SecurityContext securityContext;
    private EgressResourceImpl resource;

    @BeforeEach
    void setUp() {
        final InMemoryEgressRegistry registry = new InMemoryEgressRegistry();
        final EgressService service = new EgressService(registry, registry, new AggregatingDecisionRecorder());
        securityContext = Mockito.mock(SecurityContext.class);
        resource = new EgressResourceImpl(() -> service, () -> securityContext);
    }

    @SuppressWarnings("unchecked")
    private void permit() {
        Mockito.when(securityContext.secureResult(Mockito.any(AppPermission.class), Mockito.any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
    }

    @SuppressWarnings("unchecked")
    private void refuse() {
        Mockito.when(securityContext.secureResult(Mockito.any(AppPermission.class), Mockito.any(Supplier.class)))
                .thenThrow(new PermissionException(null, "no permission"));
    }

    @Test
    void testEveryMethodNeedsTheEgressPermission() {
        refuse();
        final EgressRuleDto rule = new EgressRuleDto(
                null, EgressKind.HTTP, "https://siem.example.org/", EgressMatch.EXACT, "SIEM", true);
        assertThatThrownBy(() -> resource.listRules()).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.saveRule(rule)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.deleteRule(1L)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.getSettings()).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.saveSettings(null)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.test(null)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.listAttempts()).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.revealAttempt("x")).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.clearAttempts(null)).isInstanceOf(PermissionException.class);
        Mockito.verify(securityContext, Mockito.times(9))
                .secureResult(Mockito.eq(AppPermission.MANAGE_EGRESS_POLICY), Mockito.any(Supplier.class));
    }

    @Test
    void testAPermittedUserCanSaveAndListAndAnInvalidRuleIsABadRequest() {
        permit();
        resource.saveRule(new EgressRuleDto(
                null, EgressKind.HTTP, "https://siem.example.org/", EgressMatch.EXACT, "SIEM", true));
        assertThat(resource.listRules()).hasSize(1);
        assertThatThrownBy(() -> resource.saveRule(new EgressRuleDto(
                null, EgressKind.HTTP, "not a url", EgressMatch.EXACT, "x", true)))
                .isInstanceOf(BadRequestException.class);
    }
}
