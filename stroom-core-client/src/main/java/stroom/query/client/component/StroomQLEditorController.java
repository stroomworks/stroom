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

package stroom.query.client.component;

import stroom.editor.client.ace.IndicatorLines;
import stroom.editor.client.ace.Marker;
import stroom.editor.client.component.CodeEditorController;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.Indicators;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.util.shared.TokenError;
import stroom.widget.component.client.Controller;

import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;
import edu.ycp.cs.dh.acegwt.client.ace.AceMarkerType;
import edu.ycp.cs.dh.acegwt.client.ace.AceRange;

import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;

/// A StroomQL query's text ([StroomQLEditor]): its editor, which completes from the help beside it,
/// and the help, which follows the query (e.g. its data source's fields) once the user stops
/// typing. It underlines the part of the query a search couldn't read.
///
/// Its owner attaches it to a [StroomQLEditor] declared in its own template.
public class StroomQLEditorController extends Controller {

    private static final int DEBOUNCE_DELAY_MS = 400;

    private final QueryHelpController queryHelp;
    private final CodeEditorController editor;

    private Timer requestTimer;
    private String currentQuery;

    private Consumer<String> onQueryChange = query -> {
    };
    private Runnable onChange = () -> {
    };

    /// Made by GIN, with its parts.
    ///
    /// @param eventBus  The application's event bus.
    /// @param queryHelp The query's help.
    /// @param editor    The query's editor.
    @Inject
    public StroomQLEditorController(final EventBus eventBus,
                                    final QueryHelpController queryHelp,
                                    final CodeEditorController editor) {
        super(eventBus);
        this.queryHelp = own(queryHelp);
        this.editor = own(editor);
        // Stops a query change waiting to be told
        onDispose(() -> {
            if (requestTimer != null) {
                requestTimer.cancel();
            }
        });
    }

    /// Attaches this to a query's text, e.g. one its owner's template declares. The text is then
    /// this controller's widget.
    ///
    /// @param view The query's text.
    /// @return This, for its owner to keep.
    public StroomQLEditorController attach(final StroomQLEditor view) {
        Objects.requireNonNull(view, "view");
        initWidget(view);
        queryHelp.attach(view.getQueryHelp());
        editor.attach(view.getEditor());
        editor.setMode(AceEditorMode.STROOM_QUERY);
        editor.getOptions().getBasicAutoCompletionOption().setOff();
        queryHelp.linkToEditor(editor);

        //noinspection unused event
        own(editor.addValueChangeHandler(event -> {
            updateQuery(editor.getText());
            onChange.run();
        }));
        own(editor.addFormatListener(() -> onChange.run()));
        return this;
    }

    /// @return The query, as edited.
    public String getText() {
        return editor.getText();
    }

    /// Shows a query, unless it is already shown, and tells the help of it.
    ///
    /// @param query The query, or null to leave the text as it is.
    public void setText(final String query) {
        if (query != null && !Objects.equals(editor.getText(), query)) {
            editor.setText(query);
            updateQuery(query);
        }
    }

    /// Tells the help, and the owner (see [#setOnQueryChange]), of the query, once it has stopped
    /// changing.
    ///
    /// @param query The query.
    public void updateQuery(final String query) {
        // Debounce requests so we don't spam the backend
        if (requestTimer != null) {
            requestTimer.cancel();
        }

        requestTimer = new Timer() {
            @Override
            public void run() {
                if (!Objects.equals(currentQuery, query)) {
                    currentQuery = query;
                    queryHelp.setQuery(query);
                    onQueryChange.accept(query);
                }
            }
        };
        requestTimer.schedule(DEBOUNCE_DELAY_MS);
    }

    /// @param readOnly Whether the query can be changed (Format is offered only when it can).
    public void setReadOnly(final boolean readOnly) {
        editor.setReadOnly(readOnly);
        editor.getFormatAction().setAvailable(!readOnly);
    }

    /// Gives the editor the focus.
    public void focus() {
        editor.focus();
    }

    /// Underlines the part of the query a search couldn't read, and marks its line.
    ///
    /// @param error Where the query couldn't be read, and why.
    public void showTokenError(final TokenError error) {
        final Indicators indicators = new Indicators();
        final DefaultLocation from = error.getFrom();
        final DefaultLocation to = error.getTo();
        indicators.add(new StoredError(Severity.ERROR, from, null, error.getText()));
        final AceRange range = AceRange.create(
                from.getLineNo() - 1,
                from.getColNo(),
                to.getLineNo() - 1,
                to.getColNo());
        final Marker marker = new Marker(range, "code-editor__error-underline", AceMarkerType.TEXT, true);
        editor.setIndicators(new IndicatorLines(indicators));
        editor.setMarkers(Collections.singletonList(marker));
    }

    /// Removes the marks of [#showTokenError], e.g. as the query runs again.
    public void clearErrors() {
        editor.setMarkers(Collections.emptyList());
        editor.setIndicators(new IndicatorLines(new Indicators()));
    }

    /// Listens for the text changing, until the registration is removed.
    ///
    /// @param handler Told the text each time.
    /// @return The registration, for the handler's owner to own.
    public HandlerRegistration addValueChangeHandler(final ValueChangeHandler<String> handler) {
        return editor.addValueChangeHandler(handler);
    }

    /// @param onChange What to run when the user changes the query, by typing or with Format.
    public void setOnChange(final Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /// @param onQueryChange Given the query once it has stopped changing, as the help is.
    public void setOnQueryChange(final Consumer<String> onQueryChange) {
        this.onQueryChange = Objects.requireNonNull(onQueryChange, "onQueryChange");
    }
}
