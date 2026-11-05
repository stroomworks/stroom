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

package stroom.egress.shared;

import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

/// The administrative interface to the egress policy. Every method needs the Manage Egress Policy permission.
@Tag(name = "Egress policy")
@Path("/egress/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface EgressResource extends RestResource, DirectRestService {

    /// Lists every rule.
    ///
    /// @return The rules.
    @GET
    @Path("/rules")
    @Operation(summary = "Lists the permitted destinations", operationId = "listEgressRules")
    List<EgressRuleDto> listRules();

    /// Adds or updates a rule. The destination is normalised before it is stored, and the comment is required.
    ///
    /// @param rule The rule. A null id adds a new rule.
    /// @return The rule as stored.
    @POST
    @Path("/rules/save")
    @Operation(summary = "Adds or updates a permitted destination", operationId = "saveEgressRule")
    EgressRuleDto saveRule(EgressRuleDto rule);

    /// Deletes a rule.
    ///
    /// @param id The id of the rule.
    /// @return True if a rule was deleted.
    @POST
    @Path("/rules/delete")
    @Operation(summary = "Deletes a permitted destination", operationId = "deleteEgressRule")
    Boolean deleteRule(Long id);

    /// Gets the mode and kill switch of each kind.
    ///
    /// @return The settings.
    @GET
    @Path("/settings")
    @Operation(summary = "Gets the mode and kill switch of each kind", operationId = "getEgressSettings")
    EgressSettingsDto getSettings();

    /// Sets the mode and kill switch of each kind.
    ///
    /// @param settings The settings.
    /// @return The settings as stored.
    @POST
    @Path("/settings/save")
    @Operation(summary = "Sets the mode and kill switch of each kind", operationId = "saveEgressSettings")
    EgressSettingsDto saveSettings(EgressSettingsDto settings);

    /// Asks whether a destination would be allowed, without sending anything.
    ///
    /// @param request The kind and destination.
    /// @return The normalised form and which rule, if any, matches.
    @POST
    @Path("/test")
    @Operation(summary = "Asks whether a destination would be allowed", operationId = "testEgressDestination")
    EgressTestResponse test(EgressTestRequest request);

    /// Lists the blocked and would-be-blocked attempts, most recent first. Destinations are masked.
    ///
    /// @return The aggregated attempts.
    @GET
    @Path("/attempts")
    @Operation(summary = "Lists blocked and reported attempts", operationId = "listEgressAttempts")
    List<EgressAttemptDto> listAttempts();

    /// Shows the unmasked destination of one attempt. The reveal is audited.
    ///
    /// @param id The id of the attempt.
    /// @return The destination, or null if the attempt is no longer held.
    @POST
    @Path("/attempts/reveal")
    @Operation(summary = "Reveals the destination of an attempt", operationId = "revealEgressAttempt")
    String revealAttempt(String id);

    /// Removes aggregated attempts. The clear is audited.
    ///
    /// @param request All of them, or those last seen before a time.
    /// @return How many were removed.
    @POST
    @Path("/attempts/clear")
    @Operation(summary = "Clears blocked and reported attempts", operationId = "clearEgressAttempts")
    Integer clearAttempts(EgressClearRequest request);
}
