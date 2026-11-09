/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.pipeline.client.component;

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipeline.shared.CheckXsltReferencesRequest;
import stroom.pipeline.shared.XsltReferenceCheckResult;
import stroom.pipeline.shared.XsltResource;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Collections;
import java.util.Objects;
import java.util.function.Supplier;

/// What an XSLT refers to ([XsltReferencesList]), checked on the server; a target's link opens it.
///
/// Asked for rather than volunteered. Nothing is checked until the tab is opened, so an author editing a
/// stylesheet is not interrupted by warnings about a document they are halfway through writing, and the
/// cost of parsing is paid only by someone who wants the answer.
///
/// The stylesheet checked is the one in the editor, including unsaved changes, because the question "does
/// this reference exist" is asked while editing and an answer about the last saved copy would be about the
/// wrong stylesheet.
public class XsltReferencesListController extends Controller {

    private static final XsltResource XSLT_RESOURCE = GWT.create(XsltResource.class);

    private final RestFactory restFactory;
    private XsltReferencesList view;
    private DocRef docRef;
    private Supplier<String> data = () -> null;

    /// Made by GIN; [#attach(XsltReferencesList)] gives it its list.
    ///
    /// @param eventBus    The application's event bus.
    /// @param restFactory Checks the references.
    @Inject
    public XsltReferencesListController(final EventBus eventBus,
                                        final RestFactory restFactory) {
        super(eventBus);
        this.restFactory = restFactory;
    }

    /// Attaches this to a list. The list is then this controller's widget, e.g. to show it as a tab.
    ///
    /// @param view The list.
    /// @return This, for its owner to keep.
    public XsltReferencesListController attach(final XsltReferencesList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnRefresh(this::check);
        view.setOnOpen(target -> OpenDocumentEvent.fire(this, target, true));
        return this;
    }

    /// Says which stylesheet to check, without checking it.
    ///
    /// @param docRef The document being checked, which is what the read permission is checked against.
    /// @param data   Gets the stylesheet to check when it is checked, normally the editor's current
    ///               content rather than the stored copy.
    public void setStylesheet(final DocRef docRef, final Supplier<String> data) {
        this.docRef = docRef;
        this.data = Objects.requireNonNull(data, "data");
    }

    /// Checks the stylesheet and shows what it refers to.
    public void check() {
        if (docRef == null) {
            view.setData(Collections.emptyList(), true);
            return;
        }

        restFactory
                .create(XSLT_RESOURCE)
                .method(resource -> resource.checkReferences(new CheckXsltReferencesRequest(docRef, data.get())))
                .onSuccess(this::onResult)
                .onFailure(error -> AlertEvent.fireError(
                        this,
                        "Unable to check references",
                        error.getMessage(),
                        null))
                .owner(this)
                .taskMonitorFactory(view.getTaskMonitor())
                .exec();
    }

    private void onResult(final XsltReferenceCheckResult result) {
        if (result == null) {
            view.setData(Collections.emptyList(), true);
            return;
        }
        // A stylesheet that will not parse is reported quietly, as a row, and never as an alert. Someone
        // mid-edit triggers this constantly, and a popup every time teaches them to dismiss popups.
        view.setData(result.getReferences(), !result.hasParseFailure());
    }
}
