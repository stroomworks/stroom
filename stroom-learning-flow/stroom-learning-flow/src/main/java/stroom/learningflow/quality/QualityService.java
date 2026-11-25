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

/// What a Smart Pipeline's Quality Assurance tab reads and records of one learner (its pipeline and AI
/// Translator element), held to the pipeline's permissions ([LearnerAccess]):
///
/// - **Volume**: streams and records translated each day of the last fortnight (UTC), by the learner's
///   translations ([Volumes]).
/// - **Cardinality**: of the records its translation in use was learned and checked on ([RegressionSet]), how
///   many became a real translation, a marked Unknown or nothing, and how many kinds of record it settled.
/// - **Samples**: those records beside what they became, newest first, for someone who may also view data.
///   Reading them reads the feed's data, so a stream the person may not read is left out by the store.
public class QualityService {

    /// How many days of volume are shown.
    static final int DAYS = 14;
    /// The most samples shown.
    static final int MOST_SAMPLES = 20;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final LearnerAccess access;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<LearnerBindings> learnerBindingsProvider;
    private final Provider<Volumes> volumesProvider;
    private final Provider<RegressionSet> regressionSetProvider;
    private final LongSupplier clock;

    /// Made by Guice.
    ///
    /// @param access                  Who may do what with a learner.
    /// @param securityContextProvider Who is asking.
    /// @param learnerBindingsProvider A learner's translations.
    /// @param volumesProvider         What its translations translated, day by day.
    /// @param regressionSetProvider   The records its translation was learned and checked on.
    @Inject
    public QualityService(final LearnerAccess access,
                          final Provider<SecurityContext> securityContextProvider,
                          final Provider<LearnerBindings> learnerBindingsProvider,
                          final Provider<Volumes> volumesProvider,
                          final Provider<RegressionSet> regressionSetProvider) {
        this(access, securityContextProvider, learnerBindingsProvider, volumesProvider, regressionSetProvider,
                System::currentTimeMillis);
    }

    /// For tests, with the time given.
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

    /// @param pipelineUuid The pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @return Its volume over the last [#DAYS] days, every day present, and the cardinality of its translation
    ///         in use.
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

    /// @param pipelineUuid The pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @return The records its translation in use was learned and checked on, newest first, at most
    ///         [#MOST_SAMPLES]; empty where none is in use.
    /// @throws PermissionException Where the person may not view data, which the records are.
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

    // The learner, where this person may view its pipeline (or, for a pipeline since deleted, its Learning Flow)
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

    // The translation in use: the binding that isn't a draft
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
