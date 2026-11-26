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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.CheckSetting;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// Wires the grid of a Learning Flow's quality checks ([CheckList]) on the Setup tab, opening a
/// [CheckEditorController] dialog to edit a check. Every check always has a setting ([CheckSet]), so checks
/// are edited, never added or removed. Nothing can be changed while the document is read only
/// ([Contexts#READ_ONLY]).
public class CheckListController extends Controller {

    private final Provider<CheckEditorController> editorProvider;
    private final CheckSet checks = new CheckSet();
    private CheckList view;
    private boolean readOnly = true;
    private Runnable onChange = () -> {
    };

    /// Created by GIN; [#attach(CheckList)] then gives it its grid.
    ///
    /// @param eventBus       The application's event bus.
    /// @param editorProvider Creates the dialog that edits a check.
    @Inject
    public CheckListController(final EventBus eventBus,
                                final Provider<CheckEditorController> editorProvider) {
        super(eventBus);
        this.editorProvider = editorProvider;
    }

    /// Attaches this controller to its grid, which is declared in the owning screen's template.
    ///
    /// @param view The grid of checks.
    /// @return This controller.
    public CheckListController attach(final CheckList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnEdit(this::edit);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
        });
        return this;
    }

    /// Shows a Learning Flow's checks, none selected.
    ///
    /// @param settings The Learning Flow's check settings. A check they don't name is shown with its default.
    public void read(final List<CheckSetting> settings) {
        checks.read(settings);
        view.clearSelection();
        show();
    }

    /// @return Every check's setting, including any edits.
    public List<CheckSetting> write() {
        return checks.write();
    }

    /// @param onChange Run when a check's setting is changed.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    private void edit() {
        final CheckSetting existing = view.getSelected();
        if (readOnly || existing == null) {
            return;
        }
        editorProvider.get().edit(this, existing, setting -> {
            if (readOnly) {
                return;
            }
            final CheckSetting current = checks.find(setting.getCheck());
            checks.replace(setting);
            show();
            view.setSelected(setting);
            if (!setting.equals(current)) {
                onChange.run();
            }
        });
    }

    private void show() {
        view.setData(checks.shown());
    }
}
