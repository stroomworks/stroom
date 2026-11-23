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

package stroom.dependency.client.component;

/// What the Structure view shows: the graph, the table of its documents, or both, the graph above the
/// table. The table is what the keyboard and a screen reader use, so it is always one choice away.
public enum StructureGraphView {

    /// The graph alone (the default).
    GRAPH("Graph", "Show the graph"),
    /// The table of the documents alone.
    TABLE("Table", "Show the documents as a table"),
    /// The graph, with the table of its documents below it.
    BOTH("Both", "Show the graph with the table of its documents below it");

    /// The view shown until the user chooses another.
    public static final StructureGraphView DEFAULT = GRAPH;

    private final String label;
    private final String title;

    StructureGraphView(final String label, final String title) {
        this.label = label;
        this.title = title;
    }

    /// @return The view's name, as its option shows it.
    public String getLabel() {
        return label;
    }

    /// @return What choosing the view does, as its option's tooltip says.
    public String getTitle() {
        return title;
    }

    /// @return Whether the graph is shown.
    public boolean isGraphShown() {
        return this != TABLE;
    }

    /// @return Whether the table of the documents is shown.
    public boolean isTableShown() {
        return this != GRAPH;
    }

    /// Reads a view from its name, as [#name()] gives it and [StructureGraphViewStore] keeps it.
    ///
    /// @param name The view's name, or null.
    /// @return The view, or [#DEFAULT] if the name is null or no view's.
    public static StructureGraphView fromName(final String name) {
        if (name != null) {
            for (final StructureGraphView view : values()) {
                if (view.name().equals(name)) {
                    return view;
                }
            }
        }
        return DEFAULT;
    }
}
