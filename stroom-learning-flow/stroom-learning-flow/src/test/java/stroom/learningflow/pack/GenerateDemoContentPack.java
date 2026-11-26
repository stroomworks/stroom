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

package stroom.learningflow.pack;

import java.nio.file.Path;
import java.nio.file.Paths;

/// Writes the Smart Pipelines demo content pack and its demo data.
///
/// The pack is a deliverable, not a test, so it is written by running this class rather than as a side effect
/// of a test run. `TestScenario51AnyFormatFromAnyFeedInAPipeline` runs the same document, with a pipeline and
/// filter per feed, over four formats in real pipelines, and [TestDemoContentPack] reads every file back.
///
/// Run it with no arguments to write `build/content/smart-pipelines-demo-v1.0.zip`, or give it a path to write
/// it somewhere else, such as the root of the repository, where the pack is committed, or the
/// `content_pack_import` directory of an instance. The demo data is written beside it, one zip per feed, in a
/// `demo-data` directory.
///
/// The output is deterministic, so regenerating an unchanged pack leaves nothing to commit.
/// `TestDemoContentPack` checks the committed pack and data against what this would write.
public final class GenerateDemoContentPack {

    private static final Path DEFAULT = Paths.get("build", "content", "smart-pipelines-demo-v1.0.zip");
    /// The directory, beside the pack, that the data zips are written to.
    private static final String DATA = "demo-data";

    private GenerateDemoContentPack() {
    }

    /// Writes the pack and the demo data.
    ///
    /// @param args The path to write the pack to, or no arguments for the default path.
    public static void main(final String[] args) {
        final Path zip = args.length > 0
                ? Paths.get(args[0])
                : DEFAULT;
        DemoContentPack.writeZip(zip);
        System.out.println("Demo content pack written to " + zip.toAbsolutePath());

        final Path beside = zip.getParent() == null
                ? Paths.get("")
                : zip.getParent();
        final Path data = beside.resolve(DATA);
        DemoContentPack.writeDataZips(data);
        System.out.println("Demo data written to " + data.toAbsolutePath());
    }
}
