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

package stroom.learningflow.fragment;

import stroom.util.pipeline.scope.PipelineScoped;

import org.xml.sax.ContentHandler;

/// Holds the handler that [FragmentTapFilter] forwards a fragment's parser output to during a serving
/// run. [PipelineFragmentRunner] sets it for the run and restores the previous handler afterwards. Like
/// [FragmentOutput], it is pipeline-scoped because the fragment is built inside the processing task's or
/// the background worker's pipeline scope.
@PipelineScoped
public class FragmentTap {

    private ContentHandler handler;

    public ContentHandler getHandler() {
        return handler;
    }

    public void setHandler(final ContentHandler handler) {
        this.handler = handler;
    }
}
