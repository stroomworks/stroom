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

package stroom.learningflow.fragment;

import stroom.util.pipeline.scope.PipelineScoped;

import org.xml.sax.ContentHandler;

/// Holds the handler that a fragment's output goes to when it is run as a nested pipeline. It is set by
/// [PipelineFragmentRunner] before each run and read by the [FragmentOutputFilter] that the runner adds
/// to the end of the fragment. It is pipeline-scoped because the nested pipeline is built in the Smart
/// Pipeline element's scope, and this is the only way to pass the handler to an element the pipeline
/// factory creates.
@PipelineScoped
public class FragmentOutput {

    private ContentHandler handler;

    public ContentHandler getHandler() {
        return handler;
    }

    public void setHandler(final ContentHandler handler) {
        this.handler = handler;
    }
}
