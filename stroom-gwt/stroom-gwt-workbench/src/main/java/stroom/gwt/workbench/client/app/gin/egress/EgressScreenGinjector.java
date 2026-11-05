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

package stroom.gwt.workbench.client.app.gin.egress;

import stroom.egress.client.component.EgressPolicyController;
import stroom.egress.client.component.EgressRuleEditorController;
import stroom.gwt.workbench.client.app.screen.ScreenGinjector;

import com.google.gwt.inject.client.GinModules;

/// The `egress` area's [ScreenGinjector]: the Egress Policy screen and its rule editor, created as Stroom's GIN
/// modules create them. Create one each time the story renders: its singletons belong to that rendering's harness.
///
/// It lists the shared base modules only (as `AppScreenGinjector` does): the screens are controllers, which need no
/// bindings.
@GinModules({
})
public interface EgressScreenGinjector extends ScreenGinjector {

    /// @return The 'Egress Policy' tab, as `EgressPolicyPlugin` opens it.
    EgressPolicyController getEgressPolicyController();

    /// @return A new 'New Egress Rule'/'Edit Egress Rule' dialog.
    EgressRuleEditorController getEgressRuleEditorController();
}
