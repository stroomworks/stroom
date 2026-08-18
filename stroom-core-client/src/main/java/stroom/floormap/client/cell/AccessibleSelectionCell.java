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

package stroom.floormap.client.cell;

import com.google.gwt.cell.client.SelectionCell;
import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link SelectionCell} that a keyboard user can reach, and that a screen reader can name.
 *
 * <p>GWT's {@code SelectionCell} renders {@code <select tabindex="-1">}. That is deliberate
 * on GWT's part rather than an oversight: a cell is not meant to be its own tab stop,
 * because {@code AbstractCellTable} is expected to move between cells with the arrow keys,
 * marking the keyboard-selected cell's wrapper focusable instead.
 *
 * <p>That leaves the control reachable only by first finding the table's own tab stop and
 * then arrowing to the right row — indirect at best, and unavailable entirely in the grids
 * where {@code MyDataGrid}'s two-arg {@code setSelectionModel} has replaced the keyboard
 * handler with an empty lambda. Dropping the {@code tabindex} puts the control in the tab
 * order directly, which is what a user tabbing through a form expects to find. See §9.5 of
 * {@code docs/floormap-accessibility.md}.
 *
 * <p>This subclass renders the same markup with the {@code tabindex} dropped, so the select
 * takes its natural place in the tab order, and adds an {@code aria-label} identifying the
 * row it belongs to — without one, every row's control announces identically. When read-only
 * it renders {@code disabled}, so the control stops presenting itself as editable in a state
 * where the field updater would discard the edit.
 *
 * <p>Restoring arrow-key navigation in {@code MyDataGrid} for the grids that lost it would
 * be the broader fix, but that is a shared-widget change with a much wider blast radius and
 * the empty handler there looks deliberate, so it needs its owner. This class is the
 * contained alternative, and it does not disturb the table's own keyboard handling — a grid
 * using these cells keeps whatever row navigation and selection it had.
 */
public class AccessibleSelectionCell extends SelectionCell {

    /**
     * Supplies the accessible name for the control on a given row, so that each row's
     * control is distinguishable rather than six controls all called "Role".
     */
    public interface RowLabelProvider {

        String getLabel(int rowIndex);
    }

    interface Template extends SafeHtmlTemplates {

        @Template("<select aria-label=\"{0}\">")
        SafeHtml select(String label);

        @Template("<select aria-label=\"{0}\" disabled=\"disabled\">")
        SafeHtml selectDisabled(String label);

        @Template("<option value=\"{0}\">{0}</option>")
        SafeHtml deselected(String option);

        @Template("<option value=\"{0}\" selected=\"selected\">{0}</option>")
        SafeHtml selected(String option);
    }

    private static final Template TEMPLATE = GWT.create(Template.class);

    private final List<String> options;
    private final Map<String, Integer> indexForOption = new HashMap<>();
    private final RowLabelProvider labelProvider;

    private boolean readOnly;

    public AccessibleSelectionCell(final List<String> options,
                                   final RowLabelProvider labelProvider) {
        // The superclass keeps its own copy, which its onBrowserEvent uses to map the
        // selected index back to a value. Ours must stay in the same order.
        super(options);
        this.options = new ArrayList<>(options);
        this.labelProvider = labelProvider;
        int index = 0;
        for (final String option : this.options) {
            indexForOption.put(option, index++);
        }
    }

    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public void render(final Context context, final String value, final SafeHtmlBuilder sb) {
        // Mirrors SelectionCell.render, including the view-data handling, so a pending edit
        // survives a redraw exactly as it does in the superclass.
        final Object key = context.getKey();
        String viewData = getViewData(key);
        if (viewData != null && viewData.equals(value)) {
            clearViewData(key);
            viewData = null;
        }

        // Null-coalesced, not just null-checked for the provider: a provider that returns
        // null would otherwise reach the generated SafeHtml escaper and throw.
        final String label = labelProvider == null
                ? ""
                : nullToEmpty(labelProvider.getLabel(context.getIndex()));
        sb.append(readOnly
                ? TEMPLATE.selectDisabled(label)
                : TEMPLATE.select(label));

        final Integer selectedIndex = indexForOption.get(viewData == null
                ? value
                : viewData);
        int index = 0;
        for (final String option : options) {
            if (selectedIndex != null && index == selectedIndex) {
                sb.append(TEMPLATE.selected(option));
            } else {
                sb.append(TEMPLATE.deselected(option));
            }
            index++;
        }
        sb.appendHtmlConstant("</select>");
    }

    private static String nullToEmpty(final String value) {
        return value == null
                ? ""
                : value;
    }
}
