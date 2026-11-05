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

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMode;
import stroom.egress.shared.EgressResource;
import stroom.egress.shared.EgressSettingsDto;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The mode and kill switch of each kind ([EgressSettingsList]). Turning on enforcement or a kill switch asks first,
/// because it can stop data leaving; turning either off does not.
public class EgressSettingsListController extends Controller {

    private static final EgressResource RESOURCE = GWT.create(EgressResource.class);

    private EgressSettingsList view;
    private final RestFactory restFactory;
    private EgressSettingsDto settings;

    /// Made by GIN; [#attach(EgressSettingsList)] gives it its list.
    ///
    /// @param eventBus    The application's event bus.
    /// @param restFactory Reads and saves the settings.
    @Inject
    public EgressSettingsListController(final EventBus eventBus,
                                        final RestFactory restFactory) {
        super(eventBus);
        this.restFactory = restFactory;
    }

    /// Attaches this to its list, which it fills from the server. The list is then this controller's widget.
    ///
    /// @return This, for its owner to keep.
    public EgressSettingsListController attach(final EgressSettingsList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnEnforceChange(this::onEnforceChange);
        view.setOnKillSwitchChange(this::onKillSwitchChange);
        refresh();
        return this;
    }

    /// Reads the settings again.
    public void refresh() {
        restFactory.create(RESOURCE)
                .method(EgressResource::getSettings)
                .onSuccess(this::show)
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void show(final EgressSettingsDto result) {
        settings = result;
        final List<EgressKindSetting> rows = new ArrayList<>();
        for (final EgressKind kind : EgressKind.values()) {
            final EgressMode mode = result.getModes().get(kind);
            final Boolean killed = result.getKillSwitches().get(kind);
            rows.add(new EgressKindSetting(
                    kind,
                    mode == null
                            ? EgressMode.REPORT_ONLY
                            : mode,
                    Boolean.TRUE.equals(killed)));
        }
        view.setData(rows);
    }

    private void onEnforceChange(final EgressKind kind, final boolean enforce) {
        final Map<EgressKind, EgressMode> modes = new EnumMap<>(EgressKind.class);
        modes.putAll(settings.getModes());
        modes.put(kind, enforce
                ? EgressMode.ENFORCE
                : EgressMode.REPORT_ONLY);
        final EgressSettingsDto changed = new EgressSettingsDto(modes, settings.getKillSwitches());
        if (enforce) {
            confirmThenSave("Enforce the egress policy for " + kind.getDisplayValue() + "? Anything that no rule "
                            + "permits will be refused from now on, and the data it was to send will not be sent.",
                    changed);
        } else {
            save(changed);
        }
    }

    private void onKillSwitchChange(final EgressKind kind, final boolean killed) {
        final Map<EgressKind, Boolean> killSwitches = new EnumMap<>(EgressKind.class);
        killSwitches.putAll(settings.getKillSwitches());
        killSwitches.put(kind, killed);
        final EgressSettingsDto changed = new EgressSettingsDto(settings.getModes(), killSwitches);
        if (killed) {
            confirmThenSave("Turn on the kill switch for " + kind.getDisplayValue() + "? Every destination of "
                            + "this kind will be refused, whatever the rules say.",
                    changed);
        } else {
            save(changed);
        }
    }

    private void confirmThenSave(final String question, final EgressSettingsDto changed) {
        ConfirmEvent.fireWarn(this, question, ok -> {
            if (ok && !isDisposed()) {
                save(changed);
            } else if (!isDisposed()) {
                // Puts the tick box back
                show(settings);
            }
        });
    }

    private void save(final EgressSettingsDto changed) {
        restFactory.create(RESOURCE)
                .method(resource -> resource.saveSettings(changed))
                .onSuccess(this::show)
                // Puts the tick box back
                .onFailure(new DefaultErrorHandler(this, () -> show(settings)))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }
}
