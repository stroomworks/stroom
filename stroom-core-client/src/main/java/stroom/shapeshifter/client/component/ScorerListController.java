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

package stroom.shapeshifter.client.component;

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// The scorer set of design §8.4 ([ScorerList]), each scorer added and edited in a
/// [ScorerSettingEditorController] dialog. A document applies each scorer at most once, so the set is
/// keyed by scorer type ([ScorerSet]) and the scorer's editor refuses a duplicate. Nothing can be
/// changed while the document is read only ([Contexts#READ_ONLY]).
public class ScorerListController extends Controller {

    private final Provider<ScorerSettingEditorController> editorProvider;
    private final ScorerSet scorers = new ScorerSet();
    private ScorerList view;
    private boolean readOnly = true;
    private Runnable onChange = () -> {
    };

    /// Made by GIN; [#attach(ScorerList)] gives it its list.
    ///
    /// @param eventBus       The application's event bus.
    /// @param editorProvider Makes the dialog that edits a scorer.
    @Inject
    public ScorerListController(final EventBus eventBus,
                                final Provider<ScorerSettingEditorController> editorProvider) {
        super(eventBus);
        this.editorProvider = editorProvider;
    }

    /// Attaches it to its list, declared in its owner's template.
    ///
    /// @param view The list.
    /// @return This.
    public ScorerListController attach(final ScorerList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnAdd(this::add);
        view.setOnEdit(this::edit);
        view.setOnRemove(this::remove);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
        });
        return this;
    }

    /// Shows a document's scorers, none selected.
    ///
    /// @param settings The document's scorers, or null for none.
    public void read(final List<ScorerSetting> settings) {
        scorers.read(settings);
        view.clearSelection();
        show();
    }

    /// @return The scorers shown, as they are now.
    public List<ScorerSetting> write() {
        return scorers.write();
    }

    /// @param onChange Run when a scorer is added, edited or removed.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    private void add() {
        if (readOnly) {
            return;
        }
        final ScorerType unused = scorers.firstUnusedType();
        if (unused == null) {
            AlertEvent.fireInfo(this, "Every scorer is already in the set.", null);
            return;
        }
        editorProvider.get().edit(
                this,
                new ScorerSetting(unused, null, null, null, null),
                scorer -> scorers.duplicate(scorer.getType(), null),
                scorer -> {
                    if (!readOnly) {
                        scorers.add(scorer);
                        show();
                        view.setSelected(scorer);
                        onChange.run();
                    }
                });
    }

    private void edit() {
        final ScorerSetting existing = view.getSelected();
        if (readOnly || existing == null) {
            return;
        }
        // Found again by its type on OK, as the set may have changed behind the editor, which isn't
        // modal
        final ScorerType type = existing.getType();
        editorProvider.get().edit(
                this,
                existing,
                scorer -> scorers.duplicate(scorer.getType(), type),
                scorer -> {
                    if (readOnly) {
                        return;
                    }
                    final ScorerSetting current = scorers.find(type);
                    if (!scorers.replace(type, scorer)) {
                        AlertEvent.fireWarn(this,
                                "The " + type.getDisplayValue()
                                + " scorer was removed while it was being edited, so the change is not kept.",
                                null);
                        return;
                    }
                    show();
                    view.setSelected(scorer);
                    if (!scorer.equals(current)) {
                        onChange.run();
                    }
                });
    }

    private void remove() {
        final ScorerSetting existing = view.getSelected();
        if (readOnly || existing == null) {
            return;
        }
        final ScorerType type = existing.getType();
        ConfirmEvent.fire(this, "Remove the " + type.getDisplayValue() + " scorer?", ok -> {
            if (ok && !isDisposed() && !readOnly) {
                scorers.remove(type);
                view.clearSelection();
                show();
                onChange.run();
            }
        });
    }

    private void show() {
        view.setData(scorers.write());
    }
}
