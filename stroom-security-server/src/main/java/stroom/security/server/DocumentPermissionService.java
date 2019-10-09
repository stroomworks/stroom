/*
 * Copyright 2017 Crown Copyright
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

package stroom.security.server;

import stroom.query.api.v2.DocRef;
import stroom.security.shared.DocumentPermissions;
import stroom.security.shared.UserRef;

import java.util.Set;

public interface DocumentPermissionService {
    DocumentPermissions getPermissionsForDocument(DocRef document);

    UserDocumentPermissions getPermissionsForUser(String userUuid);

    void addPermission(UserRef userRef, DocRef document, String permission);

    void removePermission(UserRef userRef, DocRef document, String permission);

    void clearDocumentPermissions(DocRef document);

    void clearUserPermissions(UserRef userRef);
}