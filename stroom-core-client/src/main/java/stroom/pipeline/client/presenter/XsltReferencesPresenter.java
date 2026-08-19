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

package stroom.pipeline.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.cell.info.client.CommandLink;
import stroom.data.grid.client.EndColumn;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipeline.shared.CheckXsltReferencesRequest;
import stroom.pipeline.shared.XsltReferenceCheckResult;
import stroom.pipeline.shared.XsltReferenceInfo;
import stroom.pipeline.shared.XsltReferenceReason;
import stroom.pipeline.shared.XsltResource;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Lists what an XSLT refers to - the documents it imports and reads, the reference data maps it reads and
 * writes, the endpoints it contacts - and what could not be determined.
 * <p>
 * Asked for rather than volunteered. Nothing is checked until this tab is opened, so an author editing a
 * stylesheet is not interrupted by warnings about a document they are halfway through writing, and the cost
 * of parsing is paid only by someone who wants the answer.
 * <p>
 * The stylesheet checked is the one in the editor, including unsaved changes, because the question "does this
 * reference exist" is asked while editing and an answer about the last saved copy would be about the wrong
 * stylesheet.
 */
public class XsltReferencesPresenter extends MyPresenterWidget<PagerView> {

    private static final XsltResource XSLT_RESOURCE = GWT.create(XsltResource.class);

    private final RestFactory restFactory;
    private final MyDataGrid<XsltReferenceInfo> dataGrid;

    @Inject
    public XsltReferencesPresenter(final EventBus eventBus,
                                   final PagerView view,
                                   final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("XSLT References");
        // No selection model, deliberately. Selecting a row here would do nothing, and adding one sets a
        // pointer cursor over the whole row - inviting a click that only the 'Resolves to' link answers.
        // MarkerListPresenter, the nearest equivalent beside the pipeline editor, does the same.
        view.setDataWidget(dataGrid);

        initTableColumns();
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferencesPresenter::describeKind).build(),
                DataGridUtil.headingBuilder("Kind").build(),
                110);

        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferenceInfo::getRawValue).build(),
                DataGridUtil.headingBuilder("Reference").build(),
                250);

        // The target is a link, because the most useful thing to do with a resolved reference is to go and
        // look at it.
        final Column<XsltReferenceInfo, CommandLink> targetColumn = DataGridUtil.commandLinkColumnBuilder(
                        buildOpenTargetCommandLink())
                .build();
        DataGridUtil.addCommandLinkFieldUpdater(targetColumn);
        dataGrid.addResizableColumn(
                targetColumn,
                DataGridUtil.headingBuilder("Resolves to").build(),
                250);

        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferencesPresenter::describeStatus).build(),
                DataGridUtil.headingBuilder("Status").build(),
                280);

        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferencesPresenter::describeLine).build(),
                DataGridUtil.headingBuilder("Line").build(),
                60);

        dataGrid.addEndColumn(new EndColumn<>());
    }

    /**
     * Check the given stylesheet and show what it refers to.
     *
     * @param docRef The document being checked, which is what the read permission is checked against.
     * @param data   The stylesheet to check, normally the editor's current content rather than the stored
     *               copy.
     */
    public void check(final DocRef docRef, final String data) {
        if (docRef == null) {
            setData(Collections.emptyList());
            return;
        }

        restFactory
                .create(XSLT_RESOURCE)
                .method(resource -> resource.checkReferences(new CheckXsltReferencesRequest(docRef, data)))
                .onSuccess(this::onResult)
                .onFailure(error -> AlertEvent.fireError(
                        XsltReferencesPresenter.this,
                        "Unable to check references",
                        error.getMessage(),
                        null))
                .taskMonitorFactory(this)
                .exec();
    }

    private void onResult(final XsltReferenceCheckResult result) {
        if (result == null) {
            setData(Collections.emptyList());
            return;
        }
        setData(result.getReferences());

        // A stylesheet that will not parse is reported quietly, as a row, and never as an alert. Someone
        // mid-edit triggers this constantly, and a popup every time teaches them to dismiss popups.
        if (result.hasParseFailure()) {
            dataGrid.setRowCount(result.getReferences().size(), false);
        }
    }

    private void setData(final List<XsltReferenceInfo> references) {
        final List<XsltReferenceInfo> rows = NullSafe.list(references);
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), true);
    }

    private Function<XsltReferenceInfo, CommandLink> buildOpenTargetCommandLink() {
        return (final XsltReferenceInfo reference) -> {
            final DocRef target = reference == null
                    ? null
                    : reference.getTarget();
            if (target == null) {
                return null;
            }
            final String name = target.getName();
            return new CommandLink(
                    name,
                    "Open " + target.getType() + " '" + name + "'.",
                    () -> OpenDocumentEvent.fire(XsltReferencesPresenter.this, target, true));
        };
    }

    private static String describeKind(final XsltReferenceInfo reference) {
        if (reference == null || reference.getKind() == null) {
            return "";
        }
        return switch (reference.getKind()) {
            case IMPORT -> "Import";
            case DICTIONARY -> "Dictionary";
            case REF_MAP_READ -> "Map read";
            case REF_MAP_WRITE -> "Map written";
            case HTTP -> "Endpoint";
            case UNANALYSED -> "Not analysed";
        };
    }

    /**
     * Says what is known and, where something is not, why - in the terms an author can act on rather than as
     * a reason code. Only a missing document and an ambiguous name are faults; the rest describe a
     * stylesheet working as intended whose value simply cannot be known without running it.
     */
    private static String describeStatus(final XsltReferenceInfo reference) {
        if (reference == null) {
            return "";
        }
        final XsltReferenceReason reason = reference.getReason();
        if (reason == null) {
            return reference.getTarget() != null
                    ? "Found"
                    : "Named, no document to resolve";
        }
        return switch (reason) {
            case NOT_FOUND -> "No such document";
            case AMBIGUOUS -> "Matches " + reference.getCandidates().size() + " documents - use the UUID";
            case DATA_DRIVEN -> "Comes from the input data";
            case PARAMETER -> "Comes from a parameter";
            case NON_LITERAL_BINDING -> "Built at run time";
            case IMPORTED -> "Declared in an imported stylesheet";
            case UNPARSEABLE -> "Could not be read";
        };
    }

    private static String describeLine(final XsltReferenceInfo reference) {
        if (reference == null || reference.getLineNumber() <= 0) {
            return "";
        }
        return String.valueOf(reference.getLineNumber());
    }
}
