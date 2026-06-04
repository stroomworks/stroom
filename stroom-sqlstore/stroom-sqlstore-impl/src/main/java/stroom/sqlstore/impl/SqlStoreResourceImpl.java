/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.sqlstore.impl;

import stroom.docref.DocRef;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.shared.SqlStoreResource;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

@AutoLogged
class SqlStoreResourceImpl implements SqlStoreResource {

    private final Provider<UpdatableSqlTemporalStore> updatableSqlTemporalStoreProvider;
    private final SecurityContext securityContext;

    @Inject
    SqlStoreResourceImpl(final Provider<UpdatableSqlTemporalStore> updatableSqlTemporalStoreProvider,
                         final SecurityContext securityContext) {
        this.updatableSqlTemporalStoreProvider = updatableSqlTemporalStoreProvider;
        this.securityContext = securityContext;
    }

    @Override
    public Boolean clear(final DocRef docRef) {
        if (!securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "User does not have EDIT permission on store " + docRef.getName());
        }
        updatableSqlTemporalStoreProvider.get().clear(docRef.getName());
        return true;
    }

    @Override
    public Long count(final DocRef docRef) {
        if (!securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "User does not have VIEW permission on store " + docRef.getName());
        }
        return updatableSqlTemporalStoreProvider.get().count(docRef);
    }
}
