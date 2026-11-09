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

import stroom.cell.client.CommandLink;
import stroom.docref.DocRef;
import stroom.pipeline.shared.XsltReferenceInfo;
import stroom.pipeline.shared.XsltReferenceReason;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// What an XSLT refers to - the documents it imports and reads, the reference data maps it reads and
/// writes, the endpoints it contacts - and what could not be determined, a row each. Read only.
public class XsltReferencesList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    PagerPanel pager;
    @UiField
    DataGrid<XsltReferenceInfo> dataGrid;

    private Consumer<DocRef> onOpen = target -> {
    };

    /// Makes an empty list.
    public XsltReferencesList() {
        initWidget(BINDER.createAndBindUi(this));
        // No selection model, deliberately. Selecting a row here would do nothing, and adding one sets a
        // pointer cursor over the whole row - inviting a click that only the 'Resolves to' link answers.
        addColumns();
    }

    /// Shows references.
    ///
    /// @param references The references, or null for none.
    /// @param exact      Whether they are all there is: false when the stylesheet could not be parsed,
    ///                   so there may be references that could not be found.
    public void setData(final List<XsltReferenceInfo> references, final boolean exact) {
        final List<XsltReferenceInfo> rows = NullSafe.list(references);
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), exact);
    }

    /// @return The task monitor that spins the pager's refresh button.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// @param onRefresh Run when the pager's refresh button is pressed.
    public void setOnRefresh(final Runnable onRefresh) {
        pager.getRefreshButton().setOnClick(onRefresh);
    }

    /// @param onOpen Given a document to open, when its link is pressed.
    public void setOnOpen(final Consumer<DocRef> onOpen) {
        this.onOpen = onOpen;
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferencesList::describeKind).build(),
                DataGridUtil.headingBuilder("Kind").build(),
                110);

        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferenceInfo::getRawValue).build(),
                DataGridUtil.headingBuilder("Reference").build(),
                250);

        // The target is a link, because the most useful thing to do with a resolved reference is to go and
        // look at it.
        final Column<XsltReferenceInfo, CommandLink> targetColumn = DataGridUtil.commandLinkColumnBuilder(
                        this::getTargetLink)
                .build();
        DataGridUtil.addCommandLinkFieldUpdater(targetColumn);
        dataGrid.addResizableColumn(
                targetColumn,
                DataGridUtil.headingBuilder("Resolves to").build(),
                250);

        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferencesList::describeStatus).build(),
                DataGridUtil.headingBuilder("Status").build(),
                280);

        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XsltReferencesList::describeLine).build(),
                DataGridUtil.headingBuilder("Line").build(),
                60);
    }

    private CommandLink getTargetLink(final XsltReferenceInfo reference) {
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
                () -> onOpen.accept(target));
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

    /// Says what is known and, where something is not, why - in the terms an author can act on rather than
    /// as a reason code. Only a missing document and an ambiguous name are faults; the rest describe a
    /// stylesheet working as intended whose value simply cannot be known without running it.
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

    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, XsltReferencesList> {

    }
}
