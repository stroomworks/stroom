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

package stroom.floormap.impl;

import stroom.event.logging.api.ObjectInfoProvider;
import stroom.floormap.shared.FloorMapDoc;

import event.logging.BaseObject;
import event.logging.OtherObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FloorMapDocObjectInfoProvider implements ObjectInfoProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(FloorMapDocObjectInfoProvider.class);

    @Override
    public BaseObject createBaseObject(final Object obj) {
        final FloorMapDoc floorMapDoc = (FloorMapDoc) obj;
        final OtherObject.Builder<Void> builder = OtherObject.builder()
                .withType(floorMapDoc.getType())
                .withId(floorMapDoc.getUuid())
                .withName(floorMapDoc.getName())
                .withDescription(floorMapDoc.getDescription());

        return builder.build();
    }

    @Override
    public String getObjectType(final Object object) {
        return object.getClass().getSimpleName();
    }
}
