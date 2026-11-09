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

package stroom.pipeline.xslt;

import stroom.dictionary.shared.DictionaryDoc;
import stroom.docref.DocRef;
import stroom.docstore.api.DocDependencies;
import stroom.docstore.api.DocDependencyService;
import stroom.docstore.api.DocFinder;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.pipeline.shared.XsltDoc;
import stroom.security.api.SecurityContext;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Re-records the dependencies of the XSLTs whose references may have changed meaning because an XSLT or
/// dictionary was created, renamed or deleted.
///
/// An XSLT refers to other documents by name, and what a name resolves to depends on which documents have
/// it, not only on the XSLT. So the dependencies recorded when an XSLT was saved go stale when another
/// document takes or gives up one of its names:
///
/// - a second XSLT called `Common` makes an import of `Common` ambiguous, and so broken;
/// - deleting or renaming one of two dictionaries called `Words` makes `stroom:dictionary('Words')`
///   unambiguous again;
/// - creating a dictionary with a name that matched nothing satisfies the reference.
///
/// On each such change this finds the XSLTs with a recorded edge to a document of that type and name, and
/// records their dependencies again, as the store does on save. Edges recorded from a name store the name
/// the XSLT used, so the stored name finds them whether or not a document by that name existed before.
///
/// Changing only an XSLT's or dictionary's content changes no name, so it needs nothing here.
///
/// Every node handles the event, so each records the same edges again. The writes are idempotent, and the
/// events are rare: they follow a user creating, renaming or deleting a document.
@Singleton
@EntityEventHandler(
        type = XsltDoc.TYPE,
        action = {EntityAction.CREATE, EntityAction.UPDATE, EntityAction.DELETE})
@EntityEventHandler(
        type = DictionaryDoc.TYPE,
        action = {EntityAction.CREATE, EntityAction.UPDATE, EntityAction.DELETE})
class XsltDependencyRefresher implements EntityEvent.Handler {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(XsltDependencyRefresher.class);

    private final Provider<DocDependencyService> docDependencyServiceProvider;
    private final Provider<XsltStore> xsltStoreProvider;
    private final Provider<DocFinder> docFinderProvider;
    private final SecurityContext securityContext;

    @Inject
    XsltDependencyRefresher(final Provider<DocDependencyService> docDependencyServiceProvider,
                            final Provider<XsltStore> xsltStoreProvider,
                            final Provider<DocFinder> docFinderProvider,
                            final SecurityContext securityContext) {
        this.docDependencyServiceProvider = docDependencyServiceProvider;
        this.xsltStoreProvider = xsltStoreProvider;
        this.docFinderProvider = docFinderProvider;
        this.securityContext = securityContext;
    }

    @Override
    public void onChange(final EntityEvent event) {
        final DocRef docRef = NullSafe.get(event, EntityEvent::getDocRef);
        if (docRef == null) {
            return;
        }
        final Set<String> names = affectedNames(event);
        if (names.isEmpty()) {
            return;
        }
        // As the processing user, as the store records dependencies on save. See
        // XsltStoreImpl.getDependencyRemapFunction.
        securityContext.asProcessingUser(() -> refresh(docRef.getType(), names));
    }

    /// @return The names whose meaning the event may have changed: the document's name, and on a rename
    /// its old name too. Empty for an update that is not a rename.
    static Set<String> affectedNames(final EntityEvent event) {
        final Set<String> names = new LinkedHashSet<>();
        final DocRef docRef = event.getDocRef();
        final DocRef oldDocRef = event.getOldDocRef();
        if (event.getAction() == EntityAction.UPDATE) {
            final boolean renamed = oldDocRef != null
                                    && !Objects.equals(oldDocRef.getName(), docRef.getName());
            if (!renamed) {
                return names;
            }
        }
        addName(names, docRef);
        addName(names, oldDocRef);
        return names;
    }

    private void refresh(final String type, final Set<String> names) {
        // The name cache is cleared for these names by its own handler for this event, but handlers run in
        // no guaranteed order, and a resolution that read the old cache entry would record exactly the
        // stale answer this exists to replace.
        docFinderProvider.get().invalidateNames(type, names);

        final DocDependencyService docDependencyService = docDependencyServiceProvider.get();
        final Set<DocRef> xslts = docDependencyService.findSourcesByTargetName(XsltDoc.TYPE, type, names);
        LOGGER.debug(() -> LogUtil.message("{} {} named {} changed, so re-recording the dependencies of {}",
                type, names.size() == 1 ? "document" : "documents", names, xslts));

        for (final DocRef xslt : xslts) {
            try {
                final DocDependencies dependencies = xsltStoreProvider.get().getDocDependencies(xslt);
                docDependencyService.setDependencies(xslt, dependencies);
            } catch (final DocumentNotFoundException e) {
                // Deleted since its edges were recorded; its own deletion removes them
                LOGGER.debug(() -> LogUtil.message("XSLT {} no longer exists", xslt), e);
            } catch (final RuntimeException e) {
                // The recorded edges are a rebuildable index, so a failure here must not fail the change
                // that caused it
                LOGGER.error(() -> LogUtil.message("Error re-recording the dependencies of XSLT {}: {}",
                        xslt, LogUtil.exceptionMessage(e)), e);
            }
        }
    }

    private static void addName(final Set<String> names, final DocRef docRef) {
        final String name = NullSafe.get(docRef, DocRef::getName);
        if (NullSafe.isNonBlankString(name)) {
            names.add(name);
        }
    }
}
