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

package stroom.shapeshifter.client;

import stroom.core.client.ContentManager;
import stroom.core.client.MenuKeys;
import stroom.core.client.MonitoringPlugin;
import stroom.document.client.DocumentPluginRegistry;
import stroom.menubar.client.event.BeforeRevealMenubarEvent;
import stroom.security.client.api.ClientSecurityContext;
import stroom.security.shared.AppPermission;
import stroom.shapeshifter.client.component.SupervisorController;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.widget.menu.client.IconMenuItem;
import stroom.widget.util.client.KeyBinding.Action;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Singleton;

/// The Supervisor of ruling A28 in the menu: a screen of its own beside Jobs and the other monitoring
/// screens, not a tab on one Shapeshifter AI document, because what a person wants to see is every
/// attempt every document has made.
@Singleton
public class ShapeshifterAiSupervisorPlugin extends MonitoringPlugin<SupervisorController> {

    /// Made by GIN, once.
    ///
    /// @param eventBus               The application's event bus.
    /// @param contentManager         Opens the tab.
    /// @param controllerProvider     Makes the Supervisor.
    /// @param securityContext        Says whether the user may see it.
    /// @param documentPluginRegistry Where the tab's type is registered, to open it again.
    @Inject
    public ShapeshifterAiSupervisorPlugin(final EventBus eventBus,
                                          final ContentManager contentManager,
                                          final Provider<SupervisorController> controllerProvider,
                                          final ClientSecurityContext securityContext,
                                          final DocumentPluginRegistry documentPluginRegistry) {
        super(eventBus, contentManager, controllerProvider, securityContext, documentPluginRegistry);
    }

    @Override
    protected void addChildItems(final BeforeRevealMenubarEvent event) {
        if (getSecurityContext().hasAppPermission(getRequiredAppPermission())) {
            event.getMenuItems().addMenuItem(MenuKeys.MONITORING_MENU,
                    new IconMenuItem.Builder()
                            .priority(12)
                            .icon(SvgImage.AI)
                            .iconColour(IconColour.GREY)
                            .text("Shapeshifter AI Supervisor")
                            .command(this::open)
                            .build());
        }
    }

    @Override
    protected AppPermission getRequiredAppPermission() {
        return AppPermission.VIEW_DATA_PERMISSION;
    }

    @Override
    protected Action getOpenAction() {
        return null;
    }

    @Override
    public String getType() {
        return SupervisorController.TAB_TYPE;
    }
}
