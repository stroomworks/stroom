/*
 * Copyright 2016 Crown Copyright
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

import stroom.pathways.client.presenter.PathwayEditPresenter.PathwayEditView;
import stroom.widget.form.client.FormGroup;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.MySplitLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class PathwayEditViewImpl extends ViewImpl implements PathwayEditView {

    private final Widget widget;

    @UiField
    MySplitLayoutPanel centreSplit;
    @UiField
    FormGroup treeGroup;
    @UiField
    SimplePanel tree;
    @UiField
    SimplePanel constraints;
    @UiField
    SimplePanel mutations;

    @Inject
    public PathwayEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        // Nothing here is typed into. What the dialog shows is read, and the parts that are clicked
        // take focus as they are clicked.
    }

    @Override
    public void setTreeWidth(final int width) {
        centreSplit.setWidgetSize(treeGroup, width);
    }

    @Override
    public void setTree(final View view) {
        tree.setWidget(view.asWidget());
    }

    @Override
    public void setMutations(final View view) {
        mutations.setWidget(view.asWidget());
    }

    @Override
    public void setConstraints(final View view) {
        constraints.setWidget(view.asWidget());
    }

    public interface Binder extends UiBinder<Widget, PathwayEditViewImpl> {

    }
}
