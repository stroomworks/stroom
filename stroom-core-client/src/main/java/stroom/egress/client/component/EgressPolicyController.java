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

import stroom.content.client.component.ContentTabController;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.widget.util.client.HasRefresh;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/// The Egress Policy screen: where Stroom may send data, and what it has refused. A destination blocked in report-only
/// mode can be turned into a rule from the list of blocked attempts.
public class EgressPolicyController extends ContentTabController implements HasRefresh {

    public static final String TAB_TYPE = "EgressPolicy";

    private final EgressSettingsListController settings;
    private final EgressRuleListController rules;
    private final EgressAttemptListController attempts;

    /// Made by GIN, with its screen and its parts.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     Its component.
    /// @param settings The mode of each kind.
    /// @param rules    The permitted destinations.
    /// @param attempts What was refused.
    @Inject
    public EgressPolicyController(final EventBus eventBus,
                                  final EgressPolicy view,
                                  final EgressSettingsListController settings,
                                  final EgressRuleListController rules,
                                  final EgressAttemptListController attempts) {
        super(eventBus);
        initWidget(view);
        this.settings = own(settings).attach(view.getSettings());
        this.rules = own(rules).attach(view.getRules());
        this.attempts = own(attempts).attach(view.getAttempts());
        this.attempts.setOnAddRule(this.rules::addFrom);
    }

    @Override
    public void refresh() {
        settings.refresh();
        rules.refresh();
        attempts.refresh();
    }

    @Override
    public SvgImage getIcon() {
        return SvgImage.SHIELD;
    }

    @Override
    public IconColour getIconColour() {
        return IconColour.GREY;
    }

    @Override
    public String getLabel() {
        return "Egress Policy";
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }
}
