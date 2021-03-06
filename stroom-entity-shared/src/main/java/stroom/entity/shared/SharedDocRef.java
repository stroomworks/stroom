/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.entity.shared;

import stroom.query.api.DocRef;
import stroom.util.shared.SharedObject;

public class SharedDocRef extends DocRef implements SharedObject {
    public SharedDocRef() {
    }

    public SharedDocRef(final String type, String uuid) {
        super(type, uuid);
    }

    public SharedDocRef(final String type, String uuid, final String name) {
        super(type, uuid, name);
    }

    public static SharedDocRef create(final DocRef docRef) {
        if (docRef == null) {
            return null;
        }

        final SharedDocRef sharedDocRef = new SharedDocRef(docRef.getType(), docRef.getUuid(), docRef.getName());
        sharedDocRef.setId(docRef.getId());
        return sharedDocRef;
    }
}
