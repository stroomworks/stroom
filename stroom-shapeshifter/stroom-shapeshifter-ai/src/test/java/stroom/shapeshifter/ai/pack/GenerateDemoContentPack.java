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

package stroom.shapeshifter.ai.pack;

import java.nio.file.Path;
import java.nio.file.Paths;

/// Writes the demo content pack.
///
/// The pack is a deliverable, not a test, so it is written by running this rather than as a side effect
/// of a test run. What proves it is `TestScenario51AnyFormatFromAnyFeedInAPipeline`, which runs the same
/// document, and a pipeline and filter per feed, over four formats in real pipelines, and
/// [TestDemoContentPack], which reads every file back.
///
/// Run it with no arguments to write `build/content/shapeshifter-ai-demo-v1.0.zip`, or give it a path to
/// write it somewhere else — the root of the repository, where the pack is committed, or the
/// `content_pack_import` directory of an instance. The demo data goes beside it, one zip per feed, in a
/// `demo-data` directory.
///
/// Writing it twice writes the same bytes, so regenerating a pack nothing has changed leaves nothing to
/// commit; `TestDemoContentPack` checks the committed pack and the committed data against what this
/// would write.
public final class GenerateDemoContentPack {

    private static final Path DEFAULT = Paths.get("build", "content", "shapeshifter-ai-demo-v1.0.zip");
    /// Where the data zips go, beside wherever the pack is written.
    private static final String DATA = "demo-data";

    private GenerateDemoContentPack() {
    }

    /// @param args Where to write the pack, or nothing for the default.
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
