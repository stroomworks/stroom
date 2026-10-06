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

/**
 * The one trace a traces store is being opened to show, and the window to show it in.
 *
 * <p>Said before the store is opened rather than after. A store reads itself as it opens, and reading
 * itself is what fetches its first page — so anything said afterwards arrives too late: a page of
 * whatever the store shows by default has already been fetched and drawn, and is then replaced. Said
 * beforehand, the store knows what it is being opened for before it fetches anything, and fetches
 * once.
 *
 * <p>Taken by whoever gets to it first, and gone once taken. A store that was closed takes it as it
 * reads itself; one that was already open is shown again rather than read again, so nothing reads and
 * the callback after opening takes it instead.
 *
 * <p>One at a time, because a reader can only ask for one at a time: this is set and taken within a
 * single click.
 */
final class TraceToShow {

    // The one asked for and not yet taken. Emptied as it is handed over, so it is acted on once
    // however many things come looking for it.
    private static TraceToShow traceToShow;

    private final DocRef docRef;
    private final String traceId;
    private final long fromMs;
    private final long toMs;

    private TraceToShow(final DocRef docRef, final String traceId, final long fromMs, final long toMs) {
        this.docRef = docRef;
        this.traceId = traceId;
        this.fromMs = fromMs;
        this.toMs = toMs;
    }

    /**
     * Says what the store should show when it next opens. Call this before opening it.
     */
    static void showOnOpen(final DocRef docRef, final String traceId, final long fromMs, final long toMs) {
        traceToShow = new TraceToShow(docRef, traceId, fromMs, toMs);
    }

    /**
     * Hands what was asked for to the given store and forgets it, or does nothing where nothing was
     * asked of that store.
     *
     * @return whether anything was asked for.
     */
    static boolean take(final DocRef ofDoc, final Taker taker) {
        if (traceToShow == null || ofDoc == null || !traceToShow.docRef.getUuid().equals(ofDoc.getUuid())) {
            return false;
        }
        final TraceToShow taken = traceToShow;
        traceToShow = null;
        taker.take(taken.traceId, taken.fromMs, taken.toMs);
        return true;
    }

    interface Taker {

        void take(String traceId, long fromMs, long toMs);
    }
}
