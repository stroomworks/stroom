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

package stroom.query.client;

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.FireAlertEventFunction;
import stroom.util.shared.ErrorMessage;
import stroom.util.shared.ErrorMessages;
import stroom.util.shared.Severity;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.shared.HasHandlers;

import java.util.List;
import java.util.stream.Collectors;

/// Shows the messages a search made (a query's Show Errors button) in an alert, as an error, a
/// warning or information by the most severe of them, most severe first.
public final class SearchErrorsAlert {

    private static ErrorMessageTemplates templates;

    private SearchErrorsAlert() {
        // Static utility
    }

    /// Shows the messages in an alert, or does nothing if there are none.
    ///
    /// @param source The source of the alert's event, e.g. the query's controller.
    /// @param errors The search's messages, or null for none.
    public static void show(final HasHandlers source, final ErrorMessages errors) {
        if (errors != null && !errors.isEmpty()) {
            if (errors.containsAny(Severity.FATAL_ERROR, Severity.ERROR)) {
                fireAlertEvent(source, errors, AlertEvent::fireError);
            } else if (errors.containsAny(Severity.WARNING)) {
                fireAlertEvent(source, errors, AlertEvent::fireWarn);
            } else if (errors.containsAny(Severity.INFO)) {
                fireAlertEvent(source, errors, AlertEvent::fireInfo);
            }
        }
    }

    private static void fireAlertEvent(final HasHandlers source,
                                       final ErrorMessages errors,
                                       final FireAlertEventFunction fireAlertEventFunction) {
        final List<ErrorMessage> errorMessages = errors.getErrorMessagesOrderedBySeverity();
        final String msg = errorMessages.size() == 1
                ? getTemplates().errorMessageCreatedSingular()
                : getTemplates().errorMessagesCreatedPlural();
        final List<String> messages = errorMessages.stream()
                .map(SearchErrorsAlert::toDisplayMessage)
                .collect(Collectors.toList());

        fireAlertEventFunction.apply(source, msg, String.join("\n", messages), null);
    }

    private static String toDisplayMessage(final ErrorMessage errorMessage) {
        if (errorMessage.getNode() == null) {
            return getTemplates().errorMessage(errorMessage.getSeverity().getDisplayValue(),
                    errorMessage.getMessage());
        }
        return getTemplates().errorMessageWithNode(errorMessage.getSeverity().getDisplayValue(),
                errorMessage.getMessage(), errorMessage.getNode());
    }

    // Made when first needed, so the class can be loaded without GWT (e.g. by a JVM test)
    private static ErrorMessageTemplates getTemplates() {
        if (templates == null) {
            templates = GWT.create(ErrorMessageTemplates.class);
        }
        return templates;
    }
}
