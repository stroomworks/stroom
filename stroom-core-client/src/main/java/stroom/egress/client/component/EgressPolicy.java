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

package stroom.egress.client.component;

import stroom.widget.component.client.Component;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// The Egress Policy screen: the mode of each kind of destination, the permitted destinations, and what was refused.
public class EgressPolicy extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    EgressSettingsList settings;
    @UiField
    EgressRuleList rules;
    @UiField
    EgressAttemptList attempts;

    /// Makes the screen, with its lists empty until their controllers fill them.
    public EgressPolicy() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @return The mode of each kind, which its controller attaches the list's controller to.
    public EgressSettingsList getSettings() {
        return settings;
    }

    /// @return The permitted destinations, which its controller attaches the list's controller to.
    public EgressRuleList getRules() {
        return rules;
    }

    /// @return What was refused, which its controller attaches the list's controller to.
    public EgressAttemptList getAttempts() {
        return attempts;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, EgressPolicy> {

    }
}
