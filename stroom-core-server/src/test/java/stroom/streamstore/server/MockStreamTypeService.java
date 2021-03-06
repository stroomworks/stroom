/*
 * Copyright 2016 Crown Copyright
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

package stroom.streamstore.server;

import stroom.entity.server.MockNamedEntityService;
import stroom.streamstore.shared.FindStreamTypeCriteria;
import stroom.streamstore.shared.StreamType;
import stroom.streamstore.shared.StreamTypeService;
import stroom.util.spring.StroomSpringProfiles;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile(StroomSpringProfiles.TEST)
@Component("streamTypeService")
public class MockStreamTypeService extends MockNamedEntityService<StreamType, FindStreamTypeCriteria>
        implements StreamTypeService {
    public MockStreamTypeService() {
        for (final StreamType streamType : StreamType.initialValues()) {
            save(streamType);
        }
    }

    @Override
    public void clear() {
        // Do nothing as we don't want to loose stream types set in constructor.
    }

    @Override
    public String getNamePattern() {
        return null;
    }

    @Override
    public Class<StreamType> getEntityClass() {
        return StreamType.class;
    }
}
