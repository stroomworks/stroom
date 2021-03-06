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

package stroom.pipeline.server.reader;

import java.io.Reader;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import stroom.pipeline.server.task.RecordDetector;
import stroom.pipeline.server.task.SteppingController;

@Component
@Scope("prototype")
public class ReaderRecordDetectorElement extends AbstractReaderElement implements RecordDetector {
    private ReaderRecordDetector recordDetector;
    private SteppingController controller;

    @Override
    protected Reader insertFilter(final Reader reader) {
        recordDetector = new ReaderRecordDetector(reader);
        recordDetector.setController(controller);
        return recordDetector;
    }

    @Override
    public void setController(final SteppingController controller) {
        this.controller = controller;
    }
}
