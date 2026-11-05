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

package stroom.egress.impl;

import stroom.egress.api.EgressRule;
import stroom.egress.api.EgressSettings;
import stroom.egress.impl.AggregatingDecisionRecorder.Attempt;
import stroom.egress.impl.EgressMatcher.CompiledRule;
import stroom.egress.shared.EgressAttemptDto;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;
import stroom.egress.shared.EgressRuleDto;
import stroom.egress.shared.EgressSettingsDto;
import stroom.egress.shared.EgressTestRequest;
import stroom.egress.shared.EgressTestResponse;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/// The logic behind the administrative interface. It validates what the administrator enters, and never changes it
/// silently: a destination that cannot be normalised is refused, not altered.
///
/// It does not check permission. The resource does that before calling it.
@Singleton
public class EgressService {

    private final EgressStore store;
    private final EgressSettings settings;
    private final AggregatingDecisionRecorder recorder;

    /// Creates the service.
    ///
    /// @param store    Where rules and settings are kept.
    /// @param settings The settings the guard reads.
    /// @param recorder The blocked attempts.
    @Inject
    public EgressService(final EgressStore store,
                         final EgressSettings settings,
                         final AggregatingDecisionRecorder recorder) {
        this.store = store;
        this.settings = settings;
        this.recorder = recorder;
    }

    /// Lists every rule. Destinations are shown in full, because an administrator has to be able to edit them.
    ///
    /// @return The rules.
    public List<EgressRuleDto> listRules() {
        return store.listRules().stream()
                .map(EgressService::toDto)
                .toList();
    }

    /// Adds or updates a rule.
    ///
    /// @param dto The rule as entered.
    /// @return The rule as stored, with the destination in its normalised form.
    /// @throws IllegalArgumentException If the rule is not valid. The message says why.
    public EgressRuleDto saveRule(final EgressRuleDto dto) {
        if (dto == null || dto.getKind() == null) {
            throw new IllegalArgumentException("A kind is required");
        }
        if (dto.getMatch() == null) {
            throw new IllegalArgumentException("Choose whether the rule matches exactly or by prefix");
        }
        if (dto.getComment() == null || dto.getComment().isBlank()) {
            throw new IllegalArgumentException("A comment is required, saying why this destination is permitted");
        }
        if (dto.getMatch() == EgressMatch.PREFIX && !dto.getKind().isPrefixAllowed()) {
            throw new IllegalArgumentException("A " + dto.getKind() + " rule can only match exactly");
        }
        final Optional<NormalisedDestination> normalised =
                EgressNormalisers.normalise(dto.getKind(), dto.getDestination());
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException(
                    "The destination is not valid for " + dto.getKind() + " and was not changed or saved");
        }
        final EgressRule stored = store.saveRule(new EgressRule(
                dto.getId(),
                dto.getKind(),
                normalised.get().text(),
                dto.getMatch(),
                dto.getComment().trim(),
                dto.isEnabled()));
        return toDto(stored);
    }

    /// Deletes a rule.
    ///
    /// @param id The id.
    /// @return True if a rule was deleted.
    public boolean deleteRule(final Long id) {
        return id != null && store.deleteRule(id);
    }

    /// Gets the settings.
    ///
    /// @return The mode and kill switch of each kind.
    public EgressSettingsDto getSettings() {
        return new EgressSettingsDto(store.getModes(), store.getKillSwitches());
    }

    /// Sets the settings.
    ///
    /// @param dto The mode and kill switch of each kind. A kind that is left out is unchanged.
    /// @return The settings as stored.
    public EgressSettingsDto saveSettings(final EgressSettingsDto dto) {
        if (dto != null) {
            if (dto.getModes() != null) {
                dto.getModes().forEach((kind, mode) -> {
                    if (kind != null && mode != null) {
                        store.setMode(kind, mode);
                    }
                });
            }
            if (dto.getKillSwitches() != null) {
                dto.getKillSwitches().forEach((kind, killed) -> {
                    if (kind != null && killed != null) {
                        store.setKilled(kind, killed);
                    }
                });
            }
        }
        return getSettings();
    }

    /// Answers "would this be allowed?" without sending anything.
    ///
    /// @param request The kind and destination.
    /// @return The normalised form and which rule matches, or why none does.
    public EgressTestResponse test(final EgressTestRequest request) {
        final EgressKind kind = request.getKind();
        final Optional<NormalisedDestination> normalised =
                EgressNormalisers.normalise(kind, request.getDestination());
        if (normalised.isEmpty()) {
            return new EgressTestResponse(null, "DENIED", null, "The destination could not be normalised");
        }
        final String shown = EgressMasking.mask(kind, normalised.get().text());
        if (settings.isKilled(kind)) {
            return new EgressTestResponse(shown, "DENIED", null, "The kill switch is on for " + kind);
        }
        final List<CompiledRule> compiled = new ArrayList<>();
        for (final EgressRule rule : store.listRules()) {
            if (rule.enabled() && rule.kind() == kind) {
                EgressNormalisers.normalise(kind, rule.destination())
                        .ifPresent(destination -> compiled.add(new CompiledRule(rule, destination)));
            }
        }
        final Optional<EgressRule> match = EgressMatcher.match(kind, normalised.get(), compiled);
        if (match.isPresent()) {
            return new EgressTestResponse(shown, "ALLOWED", match.get().id(), "Permitted by rule " + match.get().id());
        }
        final boolean enforced = settings.getMode(kind) == EgressMode.ENFORCE;
        return new EgressTestResponse(shown,
                enforced
                        ? "DENIED"
                        : "WOULD_DENY",
                null,
                "No rule permits this destination");
    }

    /// Lists the aggregated attempts, destinations masked.
    ///
    /// @return The attempts, most recent first.
    public List<EgressAttemptDto> listAttempts() {
        return recorder.getAttempts().stream()
                .map(EgressService::toDto)
                .toList();
    }

    /// The unmasked destination of one attempt.
    ///
    /// @param id The id from [#listAttempts()].
    /// @return The destination, or null if the attempt is no longer held.
    public String revealAttempt(final String id) {
        return recorder.getAttempts().stream()
                .filter(attempt -> idOf(attempt).equals(id))
                .map(attempt -> attempt.key().destination())
                .findFirst()
                .orElse(null);
    }

    /// Clears attempts.
    ///
    /// @param beforeMs Clear those last seen before this time, or null to clear all.
    /// @return How many were removed.
    public int clearAttempts(final Long beforeMs) {
        return beforeMs == null
                ? recorder.clear()
                : recorder.clearOlderThan(Instant.ofEpochMilli(beforeMs));
    }

    private static EgressRuleDto toDto(final EgressRule rule) {
        return new EgressRuleDto(
                rule.id(),
                rule.kind(),
                rule.destination(),
                rule.match(),
                rule.comment(),
                rule.enabled());
    }

    private static EgressAttemptDto toDto(final Attempt attempt) {
        return new EgressAttemptDto(
                idOf(attempt),
                attempt.key().kind(),
                EgressMasking.mask(attempt.key().kind(), attempt.key().destination()),
                attempt.key().sink(),
                attempt.key().pipeline(),
                attempt.key().feed(),
                attempt.key().outcome().name(),
                attempt.count(),
                attempt.firstSeen().toEpochMilli(),
                attempt.lastSeen().toEpochMilli(),
                attempt.reason());
    }

    private static String idOf(final Attempt attempt) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(attempt.key().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
