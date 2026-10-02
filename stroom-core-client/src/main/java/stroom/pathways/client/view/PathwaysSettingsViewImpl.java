/*
 * Copyright 2025 Crown Copyright
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

package stroom.pathways.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.pathways.client.presenter.PathwaysSettingsPresenter.PathwaysSettingsView;
import stroom.pathways.client.presenter.PathwaysSettingsUiHandlers;
import stroom.planb.client.view.SharedFileStoreSettingsWidget;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.util.shared.NullSafe;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;
import stroom.widget.customdatebox.client.DurationPicker;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PathwaysSettingsViewImpl
        extends ViewWithUiHandlers<PathwaysSettingsUiHandlers>
        implements PathwaysSettingsView, ReadOnlyChangeHandler {

    private final Widget widget;
    private final SharedFileStoreSettingsWidget sharedFileStoreWidget;

    @UiField
    SimplePanel sharedFileStore;
    @UiField
    SimplePanel infoFeed;
    @UiField
    DurationPicker temporalOrderingTolerance;
    @UiField
    TextArea ignoredSpanNames;
    @UiField
    TextArea ignoredAttributes;
    @UiField
    CustomCheckBox allowPathwayCreation;
    @UiField
    CustomCheckBox allowPathwayMutation;
    @UiField
    CustomCheckBox allowConstraintCreation;
    @UiField
    CustomCheckBox allowConstraintMutation;

    @Inject
    public PathwaysSettingsViewImpl(final Binder binder,
                                    final SharedFileStoreSettingsWidget sharedFileStoreWidget) {
        widget = binder.createAndBindUi(this);
        this.sharedFileStoreWidget = sharedFileStoreWidget;
        sharedFileStoreWidget.setUiHandlers(this::fireChange);
        sharedFileStore.setWidget(sharedFileStoreWidget.asWidget());
        temporalOrderingTolerance.smallTimeMode();
        temporalOrderingTolerance.setValue(new SimpleDuration(0, TimeUnit.NANOSECONDS));
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setInfoFeedView(final View view) {
        this.infoFeed.setWidget(view.asWidget());
    }

    @Override
    public SimpleDuration getTemporalOrderingTolerance() {
        return temporalOrderingTolerance.getValue();
    }

    @Override
    public void setTemporalOrderingTolerance(final SimpleDuration temporalOrderingTolerance) {
        if (temporalOrderingTolerance == null) {
            this.temporalOrderingTolerance.setValue(new SimpleDuration(0, TimeUnit.NANOSECONDS));
        } else {
            this.temporalOrderingTolerance.setValue(temporalOrderingTolerance);
        }
    }

    @Override
    public List<String> getIgnoredSpanNames() {
        return textToList(ignoredSpanNames.getValue());
    }

    @Override
    public void setIgnoredSpanNames(final List<String> names) {
        ignoredSpanNames.setValue(listToText(names));
    }

    @Override
    public List<String> getIgnoredAttributes() {
        return textToList(ignoredAttributes.getValue());
    }

    @Override
    public void setIgnoredAttributes(final List<String> names) {
        ignoredAttributes.setValue(listToText(names));
    }

    // One name a line, which is how a list of them is read and written by hand. A blank line is not a
    // name, and a box with nothing in it is no list at all rather than a list of one empty name.
    private static String listToText(final List<String> list) {
        return NullSafe.isEmptyCollection(list)
                ? ""
                : list.stream().collect(Collectors.joining("\n"));
    }

    private static List<String> textToList(final String text) {
        if (NullSafe.isBlankString(text)) {
            return null;
        }
        final List<String> list = new ArrayList<>();
        for (final String part : text.split("\n")) {
            if (!NullSafe.isBlankString(part)) {
                list.add(part.trim());
            }
        }
        return list.isEmpty()
                ? null
                : list;
    }

    @Override
    public boolean isAllowPathwayCreation() {
        return allowPathwayCreation.getValue();
    }

    @Override
    public void setAllowPathwayCreation(final boolean allowPathwayCreation) {
        this.allowPathwayCreation.setValue(allowPathwayCreation);
    }

    @Override
    public boolean isAllowPathwayMutation() {
        return allowPathwayMutation.getValue();
    }

    @Override
    public void setAllowPathwayMutation(final boolean allowPathwayMutation) {
        this.allowPathwayMutation.setValue(allowPathwayMutation);
    }

    @Override
    public boolean isAllowConstraintCreation() {
        return allowConstraintCreation.getValue();
    }

    @Override
    public void setAllowConstraintCreation(final boolean allowConstraintCreation) {
        this.allowConstraintCreation.setValue(allowConstraintCreation);
    }

    @Override
    public boolean isAllowConstraintMutation() {
        return allowConstraintMutation.getValue();
    }

    @Override
    public void setAllowConstraintMutation(final boolean allowConstraintMutation) {
        this.allowConstraintMutation.setValue(allowConstraintMutation);
    }

    @Override
    public SharedFileStoreSettings getSharedFileStore() {
        return sharedFileStoreWidget.getSharedFileStore();
    }

    @Override
    public void setSharedFileStoreLocked(final boolean locked) {
        sharedFileStoreWidget.setSharedFileStoreLocked(locked);
    }

    @Override
    public void setSharedFileStore(final SharedFileStoreSettings settings) {
        sharedFileStoreWidget.setSharedFileStore(settings);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        temporalOrderingTolerance.setEnabled(!readOnly);
        ignoredSpanNames.setEnabled(!readOnly);
        ignoredAttributes.setEnabled(!readOnly);
        sharedFileStoreWidget.onReadOnly(readOnly);
    }

    @UiHandler("temporalOrderingTolerance")
    public void onTemporalOrderingTolerance(final ValueChangeEvent<SimpleDuration> e) {
        fireChange();
    }

    // As the reader types, rather than when they leave the box. A text widget only says its value
    // changed on the browser's own change event, which is the moment it loses focus — so a name typed
    // and then saved straight away would be saved from a document that did not know it had been
    // edited. The pair of them covers both typing and a value arriving any other way, such as a paste
    // made with the mouse.
    @UiHandler("ignoredSpanNames")
    public void onIgnoredSpanNamesTyped(final KeyUpEvent e) {
        fireChange();
    }

    @UiHandler("ignoredSpanNames")
    public void onIgnoredSpanNames(final ValueChangeEvent<String> e) {
        fireChange();
    }

    @UiHandler("ignoredAttributes")
    public void onIgnoredAttributesTyped(final KeyUpEvent e) {
        fireChange();
    }

    @UiHandler("ignoredAttributes")
    public void onIgnoredAttributes(final ValueChangeEvent<String> e) {
        fireChange();
    }

    @UiHandler("allowPathwayCreation")
    public void onAllowPathwayCreation(final ValueChangeEvent<Boolean> e) {
        fireChange();
    }

    @UiHandler("allowPathwayMutation")
    public void onAllowPathwayMutation(final ValueChangeEvent<Boolean> e) {
        fireChange();
    }

    @UiHandler("allowConstraintCreation")
    public void onAllowConstraintCreation(final ValueChangeEvent<Boolean> e) {
        fireChange();
    }

    @UiHandler("allowConstraintMutation")
    public void onAllowConstraintMutation(final ValueChangeEvent<Boolean> e) {
        fireChange();
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    public interface Binder extends UiBinder<Widget, PathwaysSettingsViewImpl> {

    }
}
