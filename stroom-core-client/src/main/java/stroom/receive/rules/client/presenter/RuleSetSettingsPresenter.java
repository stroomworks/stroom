/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.receive.rules.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocumentEditPresenter;
import stroom.query.api.ExpressionOperator;
import stroom.query.client.ExpressionTreePresenter;
import stroom.query.client.presenter.SimpleFieldSelectionListModel;
import stroom.receive.rules.client.presenter.RuleSetSettingsPresenter.RuleSetSettingsView;
import stroom.receive.rules.shared.ReceiveDataRule;
import stroom.receive.rules.shared.ReceiveDataRules;
import stroom.receive.rules.shared.RuleAction;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectEvent;

import com.google.gwt.dom.client.Style.BorderStyle;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.List;

public class RuleSetSettingsPresenter
        extends DocumentEditPresenter<RuleSetSettingsView, ReceiveDataRules> {

    private final RuleSetListPresenter listPresenter;
    private final ExpressionTreePresenter expressionPresenter;
    private final Provider<RulePresenter> editRulePresenterProvider;
    private final SimpleFieldSelectionListModel fieldSelectionBoxModel = new SimpleFieldSelectionListModel();
    private List<ReceiveDataRule> rules;

    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView copyButton;
    private final ButtonView disableButton;
    private final ButtonView deleteButton;
    private final ButtonView moveUpButton;
    private final ButtonView moveDownButton;

    @Inject
    public RuleSetSettingsPresenter(final EventBus eventBus,
                                    final RuleSetSettingsView view,
                                    final RuleSetListPresenter listPresenter,
                                    final ExpressionTreePresenter expressionPresenter,
                                    final Provider<RulePresenter> editRulePresenterProvider) {
        super(eventBus, view);
        this.listPresenter = listPresenter;
        this.expressionPresenter = expressionPresenter;
        this.editRulePresenterProvider = editRulePresenterProvider;

        getView().setTableView(listPresenter.getView());
        getView().setExpressionView(expressionPresenter.getView());

        // Stop users from selecting expression items.
        expressionPresenter.setSelectionModel(null);

        addButton = listPresenter.add(SvgPresets.ADD);
        editButton = listPresenter.add(SvgPresets.EDIT);
        copyButton = listPresenter.add(SvgPresets.COPY);
        disableButton = listPresenter.add(SvgPresets.DISABLE);
        deleteButton = listPresenter.add(SvgPresets.DELETE);
        moveUpButton = listPresenter.add(SvgPresets.UP);
        moveDownButton = listPresenter.add(SvgPresets.DOWN);

        listPresenter.getView().asWidget().getElement().getStyle().setBorderStyle(BorderStyle.NONE);

        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(addButton.addClickHandler(this::addRuleButtonClickHandler));
        registerHandler(editButton.addClickHandler(this::editButtonClickHandler));
        registerHandler(copyButton.addClickHandler(this::copyRuleButtonClickHandler));
        registerHandler(disableButton.addClickHandler(this::disableButtonClickHandler));
        registerHandler(deleteButton.addClickHandler(this::deleteButtonClickHandler));
        registerHandler(moveUpButton.addClickHandler(this::moveUpButtonClickHandler));
        registerHandler(moveDownButton.addClickHandler(this::moveDownButtonClickHandler));

        registerHandler(listPresenter.getSelectionModel().addSelectionHandler(this::listSelectionHandler));

        super.onBind();
    }

    private void addRuleButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            add();
        }
    }

    private void editButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            final ReceiveDataRule selected = listPresenter.getSelectionModel().getSelected();
            if (selected != null) {
                edit(selected);
            }
        }
    }

    private void copyRuleButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            final ReceiveDataRule selected = listPresenter.getSelectionModel().getSelected();
            if (selected != null) {
                final ReceiveDataRule newRule = new ReceiveDataRule(
                        selected.getRuleNumber() + 1,
                        System.currentTimeMillis(),
                        selected.getName(),
                        selected.isEnabled(),
                        selected.getExpression(),
                        selected.getAction());

                final int index = rules.indexOf(selected);

                if (index < rules.size() - 1) {
                    rules.add(index + 1, newRule);
                } else {
                    rules.add(newRule);
                }

                update();
                listPresenter.getSelectionModel().setSelected(newRule);
            }
        }
    }

    private void disableButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            final ReceiveDataRule selected = listPresenter.getSelectionModel().getSelected();
            if (selected != null) {
                final ReceiveDataRule newRule = new ReceiveDataRule(
                        selected.getRuleNumber(),
                        selected.getCreationTime(),
                        selected.getName(),
                        !selected.isEnabled(),
                        selected.getExpression(),
                        selected.getAction());
                final int index = rules.indexOf(selected);
                rules.remove(index);
                rules.add(index, newRule);
                listPresenter.getSelectionModel().setSelected(newRule);
                update();
                setDirty(true);
            }
        }
    }

    private void deleteButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            ConfirmEvent.fire(this, "Are you sure you want to delete this item?", ok -> {
                if (ok) {
                    final ReceiveDataRule rule = listPresenter.getSelectionModel().getSelected();
                    rules.remove(rule);
                    listPresenter.getSelectionModel().clear();
                    update();
                    setDirty(true);
                }
            });
        }
    }

    private void moveUpButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            final ReceiveDataRule rule = listPresenter.getSelectionModel().getSelected();
            if (rule != null) {
                int index = rules.indexOf(rule);
                if (index > 0) {
                    index--;
                    moveRule(rule, index);
                }
            }
        }
    }

    private void moveDownButtonClickHandler(final ClickEvent event) {
        if (!isReadOnly() && rules != null) {
            final ReceiveDataRule rule = listPresenter.getSelectionModel().getSelected();
            if (rule != null) {
                int index = rules.indexOf(rule);
                if (index < rules.size() - 1) {
                    index++;
                    moveRule(rule, index);
                }
            }
        }
    }

    private void moveRule(final ReceiveDataRule rule, final int index) {
        rules.remove(rule);
        rules.add(index, rule);
        update();
        setDirty(true);

        // Re-select the rule.
        listPresenter.getSelectionModel().setSelected(rules.get(index));
    }

    private void listSelectionHandler(final MultiSelectEvent selectEvent) {
        if (!isReadOnly()) {
            final ReceiveDataRule rule = listPresenter.getSelectionModel().getSelected();
            if (rule != null) {
                expressionPresenter.read(rule.getExpression());
                if (selectEvent.getSelectionType().isDoubleSelect()) {
                    edit(rule);
                }
            } else {
                expressionPresenter.read(null);
            }
            updateButtons();
        }
    }


    private void add() {
        final ReceiveDataRule newRule = new ReceiveDataRule(
                0,
                System.currentTimeMillis(),
                "",
                true,
                ExpressionOperator.builder().build(),
                RuleAction.RECEIVE);
        final RulePresenter editRulePresenter = editRulePresenterProvider.get();
        editRulePresenter.read(newRule, fieldSelectionBoxModel);

        showRulePresenter(editRulePresenter, () -> {
            final ReceiveDataRule rule = editRulePresenter.write();
            rules.add(0, rule);
            update();
            listPresenter.getSelectionModel().setSelected(rule);
            setDirty(true);
        });
    }

    private void edit(final ReceiveDataRule existingRule) {
        final RulePresenter editRulePresenter = editRulePresenterProvider.get();
        editRulePresenter.read(existingRule, fieldSelectionBoxModel);

        showRulePresenter(editRulePresenter, () -> {
            final ReceiveDataRule rule = editRulePresenter.write();
            final int index = rules.indexOf(existingRule);
            rules.remove(index);
            rules.add(index, rule);

            update();
            listPresenter.getSelectionModel().setSelected(rule);

            // Only mark the policies as dirty if the rule was actually changed.
            if (!existingRule.equals(rule)) {
                setDirty(true);
            }
        });
    }


    private void showRulePresenter(final RulePresenter rulePresenter,
                                   final Runnable okHandler) {
        final PopupSize popupSize = PopupSize.resizable(800, 600);
        ShowPopupEvent.builder(rulePresenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption("Edit Rule")
                .onShow(e -> listPresenter.focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        okHandler.run();
                    }
                    e.hide();
                })
                .fire();
    }

    @Override
    protected void onRead(final DocRef docRef, final ReceiveDataRules document, final boolean readOnly) {
        updateButtons();

        if (document != null) {
            fieldSelectionBoxModel.clear();
            fieldSelectionBoxModel.addItems(document.getFields());
            this.rules = document.getRules();
            listPresenter.getSelectionModel().clear();
            setDirty(false);
            update();
        }
    }

    @Override
    protected ReceiveDataRules onWrite(final ReceiveDataRules document) {
        return document;
    }

    private void update() {
        if (rules != null) {
            // Set rule numbers on all of the rules for display purposes.
            for (int i = 0; i < rules.size(); i++) {
                final ReceiveDataRule rule = rules.get(i);
                final ReceiveDataRule newRule = new ReceiveDataRule(
                        i + 1,
                        rule.getCreationTime(),
                        rule.getName(),
                        rule.isEnabled(),
                        rule.getExpression(),
                        rule.getAction());
                rules.set(i, newRule);
            }
            listPresenter.setData(rules);
        }
        updateButtons();
    }

    private void updateButtons() {
        final boolean loadedPolicy = rules != null;
        final ReceiveDataRule selection = listPresenter.getSelectionModel().getSelected();
        final boolean selected = loadedPolicy && selection != null;
        int index = -1;
        if (selected) {
            index = rules.indexOf(selection);
        }

        if (selection != null && selection.isEnabled()) {
            disableButton.setTitle("Disable");
        } else {
            disableButton.setTitle("Enable");
        }

        addButton.setEnabled(!isReadOnly() && loadedPolicy);
        editButton.setEnabled(!isReadOnly() && selected);
        copyButton.setEnabled(!isReadOnly() && selected);
        disableButton.setEnabled(!isReadOnly() && selected);
        deleteButton.setEnabled(!isReadOnly() && selected);
        moveUpButton.setEnabled(!isReadOnly() && selected && index > 0);
        moveDownButton.setEnabled(!isReadOnly() && selected && index >= 0 && index < rules.size() - 1);
    }

    public interface RuleSetSettingsView extends View {

        void setTableView(View view);

        void setExpressionView(View view);
    }
}
