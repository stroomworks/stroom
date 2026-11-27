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

package stroom.learningflow.quality;

import stroom.learningflow.doc.LearnerAccess;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.QualitySample;
import stroom.learningflow.shared.RecordKind;
import stroom.learningflow.shared.VolumeDay;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.RegressionSet;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/// Provides what a Smart Pipeline's Quality Assurance tab shows about one learner (a pipeline and its Smart
/// Pipeline element). Access is checked against the pipeline's permissions by [LearnerAccess].
///
/// The tab shows:
///
/// - **Volume**: the streams and records translated on each day of the last fortnight (UTC) by any of the
///   learner's bindings ([Volumes]).
/// - **Cardinality**: of the records the serving binding was learned and checked on (its corpus, in
///   [RegressionSet]), how many became a real translation, a marked Unknown or no event, and how many
///   distinct target kinds of record there are.
/// - **Samples**: those records beside what they became, newest first, for a user who also has permission to
///   view data. The records are the feed's data, so the store leaves out any stream the user may not read.
public class QualityService {

    /// How many days of volume are shown, ending today.
    static final int DAYS = 14;
    /// The maximum number of samples returned.
    static final int MOST_SAMPLES = 20;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final LearnerAccess access;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<LearnerBindings> learnerBindingsProvider;
    private final Provider<Volumes> volumesProvider;
    private final Provider<RegressionSet> regressionSetProvider;
    private final LongSupplier clock;

    /// Creates the service using the system clock.
    ///
    /// @param access                  Checks whether the user may view a learner.
    /// @param securityContextProvider The current user, for the view-data permission check.
    /// @param learnerBindingsProvider Supplies a learner's bindings.
    /// @param volumesProvider         Supplies the daily volumes translated by the bindings.
    /// @param regressionSetProvider   Supplies the corpus records the serving binding was accepted on.
    @Inject
    public QualityService(final LearnerAccess access,
                          final Provider<SecurityContext> securityContextProvider,
                          final Provider<LearnerBindings> learnerBindingsProvider,
                          final Provider<Volumes> volumesProvider,
                          final Provider<RegressionSet> regressionSetProvider) {
        this(access, securityContextProvider, learnerBindingsProvider, volumesProvider, regressionSetProvider,
                System::currentTimeMillis);
    }

    /// For tests, with a supplied clock.
    QualityService(final LearnerAccess access,
                   final Provider<SecurityContext> securityContextProvider,
                   final Provider<LearnerBindings> learnerBindingsProvider,
                   final Provider<Volumes> volumesProvider,
                   final Provider<RegressionSet> regressionSetProvider,
                   final LongSupplier clock) {
        this.access = access;
        this.securityContextProvider = securityContextProvider;
        this.learnerBindingsProvider = learnerBindingsProvider;
        this.volumesProvider = volumesProvider;
        this.regressionSetProvider = regressionSetProvider;
        this.clock = clock;
    }

    /// Builds the volume and cardinality report for one learner.
    ///
    /// @param pipelineUuid The UUID of the pipeline.
    /// @param elementId    The id of its Smart Pipeline element.
    /// @return Its volume over the last [#DAYS] days, with a zero row for each day with no streams, and the
    ///         cardinality of its serving binding's corpus.
    public QualityReport report(final String pipelineUuid, final String elementId) {
        final Learner learner = readable(pipelineUuid, elementId);
        final List<Binding> bindings = learnerBindingsProvider.get().forLearner(learner);
        final long firstDay = startOfDay(clock.getAsLong()) - (DAYS - 1) * DAY_MS;
        final Map<Long, VolumeDay> found = new HashMap<>();
        volumesProvider.get().perDay(bindings.stream().map(Binding::getUuid).toList(), firstDay)
                .forEach(day -> found.put(day.getDayMs(), day));
        final List<VolumeDay> days = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            final long dayMs = firstDay + day * DAY_MS;
            days.add(Objects.requireNonNullElse(found.get(dayMs), new VolumeDay(dayMs, 0L, 0L)));
        }

        final String serving = serving(bindings).orElse(null);
        final List<Accepted> accepted = serving == null
                ? List.of()
                : regressionSetProvider.get().accepted(serving);
        int translated = 0;
        int unknown = 0;
        int none = 0;
        for (final Accepted entry : accepted) {
            switch (entry.kind()) {
                case TRANSLATION -> translated++;
                case UNKNOWN -> unknown++;
                default -> none++;
            }
        }
        final int kinds = (int) accepted.stream()
                .map(Accepted::target)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return new QualityReport(days, serving, translated, unknown, none, kinds);
    }

    /// Gets sample records from the learner's serving binding's corpus, each beside what it became.
    ///
    /// @param pipelineUuid The UUID of the pipeline.
    /// @param elementId    The id of its Smart Pipeline element.
    /// @return The records the serving binding was learned and checked on, newest first, at most
    ///         [#MOST_SAMPLES]; empty if no binding is serving.
    /// @throws PermissionException If the user lacks permission to view data, since the records are feed data.
    public List<QualitySample> samples(final String pipelineUuid, final String elementId) {
        final Learner learner = readable(pipelineUuid, elementId);
        if (!securityContextProvider.get().hasAppPermission(AppPermission.VIEW_DATA_PERMISSION)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to view data, which the samples are");
        }
        final Optional<String> serving = serving(learnerBindingsProvider.get().forLearner(learner));
        if (serving.isEmpty()) {
            return List.of();
        }
        final List<Accepted> accepted = regressionSetProvider.get().accepted(serving.get());
        final List<QualitySample> samples = new ArrayList<>();
        for (int i = accepted.size() - 1; i >= 0 && samples.size() < MOST_SAMPLES; i--) {
            final Accepted entry = accepted.get(i);
            samples.add(new QualitySample(entry.text(), entry.record(), entry.output(), kind(entry.kind()),
                    entry.score(), entry.acceptedMs(), entry.target()));
        }
        return samples;
    }

    // Returns the learner if the user may view its pipeline (or, for a deleted pipeline, its Learning Flow)
    private Learner readable(final String pipelineUuid, final String elementId) {
        if (NullSafe.isBlankString(pipelineUuid) || NullSafe.isBlankString(elementId)) {
            throw new IllegalArgumentException("Say which pipeline and which of its elements");
        }
        final Learner learner = new Learner(pipelineUuid, elementId);
        if (!access.mayLearner(learner, access.documentOf(learner).orElse(null), DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read the pipeline of element " + elementId);
        }
        return learner;
    }

    // The UUID of the serving binding, which is the one that isn't a draft
    private static Optional<String> serving(final List<Binding> bindings) {
        return bindings.stream()
                .filter(binding -> !binding.isDraft())
                .map(Binding::getUuid)
                .findFirst();
    }

    private static RecordKind kind(final RegressionSet.Kind kind) {
        return switch (kind) {
            case TRANSLATION -> RecordKind.TRANSLATION;
            case UNKNOWN -> RecordKind.UNKNOWN;
            case NONE -> RecordKind.NONE;
        };
    }

    private static long startOfDay(final long ms) {
        return Math.floorDiv(ms, DAY_MS) * DAY_MS;
    }
}
