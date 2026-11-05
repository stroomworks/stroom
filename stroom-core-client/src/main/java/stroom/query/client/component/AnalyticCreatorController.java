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

package stroom.query.client.component;

import stroom.alert.client.event.AlertEvent;
import stroom.analytics.shared.AnalyticProcessType;
import stroom.analytics.shared.AnalyticRuleDoc;
import stroom.analytics.shared.AnalyticRuleResource;
import stroom.analytics.shared.NotificationConfig;
import stroom.analytics.shared.NotificationDestinationType;
import stroom.analytics.shared.NotificationStreamDestination;
import stroom.analytics.shared.QueryLanguageVersion;
import stroom.analytics.shared.ReportDoc;
import stroom.analytics.shared.ReportResource;
import stroom.analytics.shared.TableBuilderAnalyticProcessConfig;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.document.client.event.ShowCreateDocumentDialogEvent;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.ExplorerResource;
import stroom.query.api.TimeRange;
import stroom.query.client.UniqueUtil;
import stroom.query.shared.QueryResource;
import stroom.ui.config.client.UiConfigCache;
import stroom.ui.config.shared.AbstractAnalyticUiDefaultConfig;
import stroom.ui.config.shared.AnalyticUiDefaultConfig;
import stroom.ui.config.shared.ReportUiDefaultConfig;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// Makes an analytic rule or a report from a StroomQL query: it checks the query, asks the user
/// where to put the new document and what to call it, writes the query, time range and the UI
/// config's defaults into it, and opens it. It has no widget of its own.
///
/// Used by a query document's Query tab ([QueryTabController]) and a dashboard's StroomQL Query
/// pane.
public class AnalyticCreatorController extends Controller {

    private static final ExplorerResource EXPLORER_RESOURCE = GWT.create(ExplorerResource.class);
    private static final AnalyticRuleResource ANALYTIC_RULE_RESOURCE = GWT.create(AnalyticRuleResource.class);
    private static final ReportResource REPORT_RESOURCE = GWT.create(ReportResource.class);
    private static final QueryResource QUERY_RESOURCE = GWT.create(QueryResource.class);

    private final RestFactory restFactory;
    private final UiConfigCache uiConfigCache;

    /// Made by GIN, with what it needs.
    ///
    /// @param eventBus      The application's event bus.
    /// @param restFactory   Checks the query and makes the rule or report.
    /// @param uiConfigCache Gives a new rule's or report's defaults.
    @Inject
    public AnalyticCreatorController(final EventBus eventBus,
                                     final RestFactory restFactory,
                                     final UiConfigCache uiConfigCache) {
        super(eventBus);
        this.restFactory = restFactory;
        this.uiConfigCache = uiConfigCache;
    }

    /// Makes an analytic rule from a query: a streaming rule, or a scheduled query if the query
    /// groups its results. Nothing is made if the query isn't valid; the user is told why.
    ///
    /// @param location  The document the new rule's folder is chosen beside, e.g. the query's.
    /// @param name      The name the new rule is first given.
    /// @param query     The StroomQL query.
    /// @param timeRange The time range the query runs over.
    public void createRule(final DocRef location,
                           final String name,
                           final String query,
                           final TimeRange timeRange) {
        uiConfigCache.get(uiConfig -> {
            if (uiConfig != null) {
                final AnalyticUiDefaultConfig analyticUiDefaultConfig = uiConfig.getAnalyticUiDefaultConfig();
                restFactory
                        .create(QUERY_RESOURCE)
                        .method(res -> res.validateQuery(query))
                        .onSuccess(validateExpressionResult -> {
                            if (!validateExpressionResult.isOk()) {
                                AlertEvent.fireError(this,
                                        validateExpressionResult.getString(),
                                        null);
                            } else {
                                AnalyticProcessType analyticProcessType = AnalyticProcessType.STREAMING;
                                if (validateExpressionResult.isGroupBy()) {
                                    analyticProcessType = AnalyticProcessType.SCHEDULED_QUERY;
                                }
                                createRule(location, name, analyticUiDefaultConfig, query, timeRange,
                                        analyticProcessType);
                            }
                        })
                        .onFailure(restError ->
                                AlertEvent.fireErrorFromException(this, restError.getException(), null))
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec();
            }
        }, taskMonitor());
    }

    /// Makes a report, a scheduled query, from a query. Nothing is made if the query isn't valid;
    /// the user is told why.
    ///
    /// @param location  The document the new report's folder is chosen beside, e.g. the query's.
    /// @param name      The name the new report is first given.
    /// @param query     The StroomQL query.
    /// @param timeRange The time range the query runs over.
    public void createReport(final DocRef location,
                             final String name,
                             final String query,
                             final TimeRange timeRange) {
        uiConfigCache.get(uiConfig -> {
            if (uiConfig != null) {
                final ReportUiDefaultConfig reportUiDefaultConfig = uiConfig.getReportUiDefaultConfig();
                restFactory
                        .create(QUERY_RESOURCE)
                        .method(res -> res.validateQuery(query))
                        .onSuccess(validateExpressionResult -> {
                            if (!validateExpressionResult.isOk()) {
                                AlertEvent.fireError(this,
                                        validateExpressionResult.getString(),
                                        null);
                            } else {
                                createReport(location, name, reportUiDefaultConfig, query, timeRange);
                            }
                        })
                        .onFailure(restError ->
                                AlertEvent.fireErrorFromException(this, restError.getException(), null))
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec();
            }
        }, taskMonitor());
    }

    private void createRule(final DocRef location,
                            final String name,
                            final AnalyticUiDefaultConfig analyticUiDefaultConfig,
                            final String query,
                            final TimeRange timeRange,
                            final AnalyticProcessType analyticProcessType) {
        final Consumer<ExplorerNode> newDocumentConsumer = newNode -> {
            final DocRef ruleDoc = newNode.getDocRef();
            loadNewRule(ruleDoc, analyticUiDefaultConfig, query, timeRange, analyticProcessType);
        };

        // First get the explorer node for the docref.
        restFactory
                .create(EXPLORER_RESOURCE)
                .method(res -> res.getFromDocRef(location))
                .onSuccess(explorerNode -> {
                    // Ask the user to create a new document.
                    ShowCreateDocumentDialogEvent.fire(
                            this,
                            "Create New Analytic Rule",
                            explorerNode,
                            AnalyticRuleDoc.TYPE,
                            name,
                            true,
                            newDocumentConsumer);
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void loadNewRule(final DocRef ruleDocRef,
                             final AnalyticUiDefaultConfig analyticUiDefaultConfig,
                             final String query,
                             final TimeRange timeRange,
                             final AnalyticProcessType analyticProcessType) {
        restFactory
                .create(ANALYTIC_RULE_RESOURCE)
                .method(res -> res.fetch(ruleDocRef.getUuid()))
                .onSuccess(doc -> {
                    // Create default config.
                    switch (analyticProcessType) {
                        case SCHEDULED_QUERY:
                            createDefaultScheduledRule(ruleDocRef, doc, analyticUiDefaultConfig, query, timeRange);
                            break;
                        case TABLE_BUILDER:
                            createDefaultTableBuilderRule(ruleDocRef, doc, analyticUiDefaultConfig, query, timeRange);
                            break;
                        default:
                            createDefaultStreamingRule(ruleDocRef, doc, analyticUiDefaultConfig, query, timeRange);
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void createDefaultStreamingRule(final DocRef ruleDocRef,
                                            final AnalyticRuleDoc doc,
                                            final AnalyticUiDefaultConfig analyticUiDefaultConfig,
                                            final String query,
                                            final TimeRange timeRange) {
        final AnalyticRuleDoc updated = doc
                .copy()
                .languageVersion(QueryLanguageVersion.STROOM_QL_VERSION_0_1)
                .query(query)
                .timeRange(timeRange)
                .analyticProcessType(AnalyticProcessType.STREAMING)
                .notifications(createDefaultNotificationConfig(analyticUiDefaultConfig))
                .errorFeed(analyticUiDefaultConfig.getDefaultErrorFeed())
                .build();
        updateRule(ruleDocRef, updated);
    }

    private void createDefaultScheduledRule(final DocRef ruleDocRef,
                                            final AnalyticRuleDoc doc,
                                            final AnalyticUiDefaultConfig analyticUiDefaultConfig,
                                            final String query,
                                            final TimeRange timeRange) {
        final AnalyticRuleDoc updated = doc
                .copy()
                .languageVersion(QueryLanguageVersion.STROOM_QL_VERSION_0_1)
                .query(query)
                .timeRange(timeRange)
                .analyticProcessType(AnalyticProcessType.SCHEDULED_QUERY)
                .notifications(createDefaultNotificationConfig(analyticUiDefaultConfig))
                .errorFeed(analyticUiDefaultConfig.getDefaultErrorFeed())
                .build();
        updateRule(ruleDocRef, updated);
    }

    private void createDefaultTableBuilderRule(final DocRef ruleDocRef,
                                               final AnalyticRuleDoc doc,
                                               final AnalyticUiDefaultConfig analyticUiDefaultConfig,
                                               final String query,
                                               final TimeRange timeRange) {
        final SimpleDuration oneHour = SimpleDuration.builder().time(1).timeUnit(TimeUnit.HOURS).build();
        final TableBuilderAnalyticProcessConfig analyticProcessConfig =
                TableBuilderAnalyticProcessConfig.builder()
                        .node(analyticUiDefaultConfig.getDefaultNode())
                        .minMetaCreateTimeMs(System.currentTimeMillis())
                        .maxMetaCreateTimeMs(null)
                        .timeToWaitForData(oneHour)
                        .build();
        final AnalyticRuleDoc updated = doc
                .copy()
                .languageVersion(QueryLanguageVersion.STROOM_QL_VERSION_0_1)
                .query(query)
                .timeRange(timeRange)
                .analyticProcessType(AnalyticProcessType.TABLE_BUILDER)
                .analyticProcessConfig(analyticProcessConfig)
                .notifications(createDefaultNotificationConfig(analyticUiDefaultConfig))
                .errorFeed(analyticUiDefaultConfig.getDefaultErrorFeed())
                .build();
        updateRule(ruleDocRef, updated);
    }

    private List<NotificationConfig> createDefaultNotificationConfig(
            final AbstractAnalyticUiDefaultConfig analyticUiDefaultConfig) {
        final NotificationStreamDestination destination =
                NotificationStreamDestination.builder()
                        .useSourceFeedIfPossible(false)
                        .destinationFeed(analyticUiDefaultConfig.getDefaultDestinationFeed())
                        .build();
        final NotificationConfig notificationConfig = NotificationConfig
                .builder()
                .uuid(UniqueUtil.generateUUID())
                .limitNotifications(false)
                .maxNotifications(100)
                .resumeAfter(SimpleDuration.builder().time(1).timeUnit(TimeUnit.HOURS).build())
                .destinationType(NotificationDestinationType.STREAM)
                .destination(destination)
                .build();
        final List<NotificationConfig> list = new ArrayList<>();
        list.add(notificationConfig);
        return list;
    }

    private void updateRule(final DocRef ruleDocRef,
                            final AnalyticRuleDoc ruleDoc) {
        restFactory
                .create(ANALYTIC_RULE_RESOURCE)
                .method(res -> res.update(ruleDocRef.getUuid(), ruleDoc))
                .onSuccess(doc -> OpenDocumentEvent.fire(
                        AnalyticCreatorController.this,
                        ruleDocRef,
                        true,
                        false))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void createReport(final DocRef location,
                              final String name,
                              final ReportUiDefaultConfig reportUiDefaultConfig,
                              final String query,
                              final TimeRange timeRange) {
        final Consumer<ExplorerNode> newDocumentConsumer = newNode -> {
            final DocRef reportDoc = newNode.getDocRef();
            loadNewReport(reportDoc, reportUiDefaultConfig, query, timeRange);
        };

        // First get the explorer node for the docref.
        restFactory
                .create(EXPLORER_RESOURCE)
                .method(res -> res.getFromDocRef(location))
                .onSuccess(explorerNode -> {
                    // Ask the user to create a new document.
                    ShowCreateDocumentDialogEvent.fire(
                            this,
                            "Create New Report",
                            explorerNode,
                            ReportDoc.TYPE,
                            name,
                            true,
                            newDocumentConsumer);
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // A report is always a scheduled query
    private void loadNewReport(final DocRef reportDocRef,
                               final ReportUiDefaultConfig reportUiDefaultConfig,
                               final String query,
                               final TimeRange timeRange) {
        restFactory
                .create(REPORT_RESOURCE)
                .method(res -> res.fetch(reportDocRef.getUuid()))
                .onSuccess(doc -> createDefaultScheduledReport(reportDocRef, doc, reportUiDefaultConfig, query,
                        timeRange))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void createDefaultScheduledReport(final DocRef reportDocRef,
                                              final ReportDoc doc,
                                              final ReportUiDefaultConfig reportUiDefaultConfig,
                                              final String query,
                                              final TimeRange timeRange) {
        final ReportDoc updated = doc
                .copy()
                .languageVersion(QueryLanguageVersion.STROOM_QL_VERSION_0_1)
                .query(query)
                .timeRange(timeRange)
                .analyticProcessType(AnalyticProcessType.SCHEDULED_QUERY)
                .notifications(createDefaultNotificationConfig(reportUiDefaultConfig))
                .errorFeed(reportUiDefaultConfig.getDefaultErrorFeed())
                .build();
        updateReport(reportDocRef, updated);
    }

    private void updateReport(final DocRef reportDocRef,
                              final ReportDoc reportDoc) {
        restFactory
                .create(REPORT_RESOURCE)
                .method(res -> res.update(reportDocRef.getUuid(), reportDoc))
                .onSuccess(doc -> OpenDocumentEvent.fire(
                        AnalyticCreatorController.this,
                        reportDocRef,
                        true,
                        false))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }
}
