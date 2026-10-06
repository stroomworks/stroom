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

package stroom.pathways.client.presenter;

import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;

import com.google.gwt.event.shared.HasHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.List;

/**
 * Opening the traces store on the one trace a row came from.
 *
 * <p>Both the paths and the changes name the trace that taught the model something, and in both the
 * next question is what that trace actually did. This takes the reader there: the store that feeds
 * this pathway, opened, narrowed to when the trace ran and filtered to its id.
 */
final class TraceOpener {

    // Either side of the moment the trace ran. The model records that alongside the moment it learnt
    // from the trace, which are not the same: a trace waits in a queue to be applied, and that wait is
    // minutes when the consumer keeps up and as long as processing is stopped for when it does not.
    // A minute is enough for a trace whose own clock disagrees slightly with the store's.
    private static final long WINDOW_MS = 60_000L;

    private TraceOpener() {
    }

    /**
     * What a reader may do with a trace id, which is nothing at all where no traces store feeds this
     * pathway — there is nowhere to take them, so nothing is offered rather than something that fails.
     */
    static List<Item> menuItems(final HasHandlers handlers,
                                final DocRef tracesDocRef,
                                final String traceId,
                                final Long ranMs) {
        if (tracesDocRef == null || NullSafe.isBlankString(traceId) || ranMs == null) {
            return List.of();
        }
        return List.of(new IconMenuItem.Builder()
                .priority(1)
                .icon(SvgImage.DOCUMENT_TRACES)
                .text("Show this trace")
                .command(() -> open(handlers, tracesDocRef, traceId, ranMs))
                .build());
    }

    private static void open(final HasHandlers handlers,
                             final DocRef tracesDocRef,
                             final String traceId,
                             final long ranMs) {
        // Left where the store can take it as it opens. A store that was closed reads itself on the
        // way in and takes it then, before it has fetched anything — which is the only moment at
        // which asking for one trace costs one fetch rather than two.
        TraceToShow.showOnOpen(tracesDocRef, traceId, ranMs - WINDOW_MS, ranMs + WINDOW_MS);
        OpenDocumentEvent.builder(handlers, tracesDocRef)
                // Said once the document is open, because the thing being asked for is not part of
                // what the document is — it is why this reader opened it.
                //
                // The moment is handed over rather than a window built from it: how a window is said
                // is the traces view's business, and it already has to say one when a bar of its own
                // histogram is clicked.
                .callbackOnOpen(presenter -> showTrace(presenter, tracesDocRef))
                .fire();
    }

    // For a store that was already open, which is shown again rather than read again — so nothing took
    // what was left above and this is what takes it.
    private static void showTrace(final MyPresenterWidget<?> presenter,
                                  final DocRef tracesDocRef) {
        if (presenter instanceof final TracesPresenter traces) {
            TraceToShow.take(tracesDocRef, traces::showTrace);
        }
    }
}
