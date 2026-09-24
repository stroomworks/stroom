/*
 * Copyright 2025 Crown Copyright
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

package stroom.pathways.client.presenter;

import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.pathways.client.presenter.PathwayEditPresenter.PathwayEditView;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayReplay;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.validation.ValidationException;

public class PathwayEditPresenter extends MyPresenterWidget<PathwayEditView> {

    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    /**
     * How much of a pathway's history is held for winding the model back. A pathway's history is
     * bounded by what is novel rather than by traffic, so this is not expected to be reached — but
     * winding back part of a history would show a model that never existed, so past this the view
     * stays on the current one and says nothing false.
     */
    private static final int MAX_HISTORY = 20000;

    private Pathway pathway;
    private List<PathwayMutation> history = Collections.emptyList();
    private boolean historyComplete;
    private final PathwayTreePresenter pathwayTreePresenter;
    private final ConstraintListPresenter constraintListPresenter;
    private final PathwayMutationListPresenter mutationListPresenter;
    private final RestFactory restFactory;
    private boolean readOnly = true;

    @Inject
    public PathwayEditPresenter(final EventBus eventBus,
                                final PathwayEditView view,
                                final PathwayTreePresenter pathwayTreePresenter,
                                final ConstraintListPresenter constraintListPresenter,
                                final PathwayMutationListPresenter mutationListPresenter,
                                final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.pathwayTreePresenter = pathwayTreePresenter;
        this.constraintListPresenter = constraintListPresenter;
        this.mutationListPresenter = mutationListPresenter;
        // The Constraints panel beside the tree already shows what the selected node holds, so the
        // tree's own Node Info panel would only repeat it.
        pathwayTreePresenter.setShowNodeInfo(false);
        view.setTree(pathwayTreePresenter.getView());
        view.setConstraints(constraintListPresenter.getView());
        view.setMutations(mutationListPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(pathwayTreePresenter.getSelectionModel()
                .addSelectionChangeHandler(e -> showConstraints()));

        registerHandler(mutationListPresenter.getSelectionModel().addSelectionHandler(e -> showModel()));

//        registerHandler(getView().getDetails().addClickHandler(e -> {
//            final Element target = e.getNativeEvent().getEventTarget().cast();
//            if (target != null) {
//                final Element node = ElementUtil.findParent(target, element ->
//                        NullSafe.isNonBlankString(element.getAttribute("uuid")), 3);
//                if (node != null) {
//                    final String uuid = node.getAttribute("uuid");
////                    AlertEvent.fireInfo(this, uuid, null);
//
//                    final PathNode pathNode = nodeMap.get(uuid);
//                    // Calculate min, max, average time.
//                    NanoTime min = NanoTime.ofSeconds(Long.MAX_VALUE);
//                    NanoTime max = NanoTime.ZERO;
//                    NanoTime sum = NanoTime.ZERO;
//                    final int count = pathNode.getSpans().size();
//
//                    final HtmlBuilder spanDetails = new HtmlBuilder();
//                    final CommonSpanBuilder commonSpanBuilder = new CommonSpanBuilder();
//                    for (final Span span : pathNode.getSpans()) {
//                        commonSpanBuilder.add(span);
//
//                        final NanoTime startTime = NanoTime.fromString(span.getStartTimeUnixNano());
//                        final NanoTime endTime = NanoTime.fromString(span.getEndTimeUnixNano());
//                        final NanoTime duration = endTime.diff(startTime);
//
//                        if (min.isGreaterThan(duration)) {
//                            min = duration;
//                        }
//                        if (max.isLessThan(duration)) {
//                            max = duration;
//                        }
//                        sum = sum.add(duration);
//                    }
//
//
//                    final Span commonSpan = commonSpanBuilder.build();
//                    final SafeHtml durations = getDurationsHtml(min, max, sum, count);
//                    spanDetails.div(div -> {
//                        div.append(durations);
//                    });
//                    spanDetails.div(div -> {
//                        div.append(getSpanHtml(commonSpan));
//                    });
//
////                    getView().setConstraints(HtmlBuilder.builder().div(d -> d.append(getConstraintsHtml(pathNode)),
////                            Attribute.className("pathway-constraints")).toSafeHtml());
//
//                    constraintListPresenter.setData(getConstraintList(pathNode), readOnly);
//
//
////                    getView().setSpans(HtmlBuilder.builder().div(d -> d.append(spanDetails.toSafeHtml()),
////                            Attribute.className("pathway-spans")).toSafeHtml());
//
//                    if (!Objects.equals(selected, node)) {
//                        if (selected != null) {
//                            selected.removeClassName("pathway-nodeName--selected");
//                        }
//                        selected = node;
//                        selected.addClassName("pathway-nodeName--selected");
//                    }
//                }
//            }
//        }));
    }


//
//    private SafeHtml getConstraintsHtml(final PathNode pathNode) {
//        final HtmlBuilder hb = new HtmlBuilder();
//
//        final Constraints constraints = pathNode.getConstraints();
//        if (constraints != null) {
//            if (constraints.getDuration() != null) {
//                append(hb, "Duration", constraints.getDuration().toString());
//            }
//            if (constraints.getFlags() != null) {
//                append(hb, "Flag", constraints.getFlags().toString());
//            }
//            if (constraints.getKind() != null) {
//                append(hb, "Kind", constraints.getKind().toString());
//            }
//            append(hb, "Attributes", "");
//
//            final Map<String, ConstraintValue> requiredAttributes = NullSafe.map(constraints.getRequiredAttributes());
//            final Map<String, ConstraintValue> optionalAttributes = NullSafe.map(constraints.getOptionalAttributes());
//            final Set<String> keys = new HashSet<>(requiredAttributes.keySet());
//            keys.addAll(optionalAttributes.keySet());
//            final List<String> sortedKeys = keys.stream().sorted().collect(Collectors.toList());
//            for (final String key : sortedKeys) {
//                final ConstraintValue required = requiredAttributes.get(key);
//                if (required != null) {
//                    hb.div(div ->
//                            append(div, key, required.toString()), Attribute.className("pathway-attributes"));
//                }
//                final ConstraintValue optional = optionalAttributes.get(key);
//                if (optional != null) {
//                    hb.div(div ->
//                            append(div, key, optional.toString()),
//                            Attribute.className("pathway-attributes-optional"));
//                }
//            }
//        }
//        return hb.toSafeHtml();
//    }
//
//    private SafeHtml getSpanHtml(final Span span) {
//        final HtmlBuilder hb = new HtmlBuilder();
//        if (span.getTraceId() != null) {
//            append(hb, "Trace Id", span.getTraceId());
//        }
//        if (span.getSpanId() != null) {
//            append(hb, "Span Id", span.getSpanId());
//        }
//        if (span.getTraceState() != null) {
//            append(hb, "Trace State", span.getTraceState());
//        }
//        if (span.getParentSpanId() != null) {
//            append(hb, "Parent Span Id", span.getParentSpanId());
//        }
//        if (span.getFlags() != -1) {
//            append(hb, "Flags", String.valueOf(span.getFlags()));
//        }
//        if (span.getName() != null) {
//            append(hb, "Name", span.getName());
//        }
//        if (span.getKind() != null) {
//            append(hb, "Kind", String.valueOf(span.getKind()));
//        }
////        if (span.getstartTimeUnixNano() != null) {
////
////        if (span.getendTimeUnixNano() != null) {
//
//        if (!NullSafe.isEmptyCollection(span.getAttributes())) {
//            append(hb, "Attributes", "");
//            hb.div(div -> {
//                for (final KeyValue keyValue : span.getAttributes()) {
//                    append(div, keyValue.getKey(), keyValue.getValue().toString());
//                }
//            }, Attribute.className("pathway-attributes"));
//        }
//        if (span.getDroppedAttributesCount() != -1) {
//            append(hb, "Dropped Attributes Count", String.valueOf(span.getDroppedAttributesCount()));
//        }
////        if (span.getevents() != null) {
//
//        if (span.getDroppedEventsCount() != -1) {
//            append(hb, "Dropped Events Count", String.valueOf(span.getDroppedEventsCount()));
//        }
////        if (span.getlinks() != null) {
//
//        if (span.getDroppedLinksCount() != -1) {
//            append(hb, "Dropped Links Count", String.valueOf(span.getDroppedLinksCount()));
//        }
//        if (span.getStatus() != null) {
//            append(hb, "Status", "");
//            hb.div(div -> {
//                if (span.getStatus().getCode() != null) {
//                    append(div, "Code", span.getStatus().getCode().toString());
//                }
//                if (span.getStatus().getMessage() != null) {
//                    append(div, "Message", span.getStatus().getMessage());
//                }
//            }, Attribute.className("pathway-attributes"));
//        }
//
//        return hb.toSafeHtml();
//    }
//
//    private void append(final HtmlBuilder hb, final String key, final String value) {
//        hb.div(div -> {
//            div.div(k -> k.append(key + ":"), Attribute.className("pathway-attributeKey"));
//            div.div(v -> v.append(value), Attribute.className("pathway-attributeValue"));
//        }, Attribute.className("pathway-attribute"));
//    }

//    private SafeHtml getDurationsHtml(final NanoTime min,
//                                      final NanoTime max,
//                                      final NanoTime sum,
//                                      final int count) {
//        final HtmlBuilder hb = new HtmlBuilder();
//        append(hb, "Calls", Integer.toString(count));
//        append(hb, "Min Duration", min.toString());
//        append(hb, "Max Duration", max.toString());
//        append(hb, "Average Duration",
//                new NanoTime(sum.getSeconds() / count, sum.getNanos() / count).toString());
//        return hb.toSafeHtml();
//    }

    // The whole history at once, so winding the model back is done here rather than asked for a step
    // at a time. It is bounded by what the model has learnt, not by how many traces went through it.
    private void fetchHistory(final DocRef docRef, final String name) {
        final FindPathwayMutationCriteria criteria = new FindPathwayMutationCriteria(
                new PageRequest(0, MAX_HISTORY),
                List.of(new CriteriaFieldSort(PathwayMutation.FIELD_TIME, false, false)),
                docRef,
                name);

        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.findMutations(criteria))
                .onSuccess(result -> {
                    // This is opened again for other pathways, so a history arriving late must not be
                    // used to wind back a model it does not belong to.
                    if (pathway == null || !name.equals(pathway.getName())) {
                        return;
                    }
                    history = result.getValues();
                    pathwayTreePresenter.setHistory(history);
                    historyComplete = history.size() >= NullSafe.getOrElse(
                            result.getPageResponse(), PageResponse::getTotal, 0L);
                    mutationListPresenter.setData(history);
                    showModel();
                })
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(this)
                .exec();
    }

    // The model as it stood at the change being looked at, or as it stands now where none is.
    private void showModel() {
        if (pathway == null) {
            return;
        }

        final Long selected = mutationListPresenter.getSelectedSequence();
        if (selected == null || !historyComplete) {
            pathwayTreePresenter.read(pathway);
            return;
        }

        final List<PathwayMutation> later = new ArrayList<>();
        for (final PathwayMutation mutation : history) {
            if (mutation.getSequence() > selected) {
                later.add(mutation);
            }
        }

        final PathNode root = PathwayReplay.rewind(pathway.getRoot(), later);
        pathwayTreePresenter.read(root == null
                ? null
                : pathway.copy().root(root).build());
        showConstraints();
    }

    // The node the tree is holding, whichever model it came from. Winding the model back picks the same
    // node out again, and the selection model treats that as no change and says nothing, so this has to
    // be asked for rather than waited for.
    private void showConstraints() {
        constraintListPresenter.setData(pathwayTreePresenter.getSelectionModel().getSelectedObject(),
                history, readOnly);
    }

    public void read(final PathwaysDoc pathwaysDoc, final Pathway pathway, final boolean readOnly) {
        this.readOnly = readOnly;
        this.pathway = pathway;
//        this.selected = null;
//
//        getView().setDetails(SafeHtmlUtils.EMPTY_SAFE_HTML);
        this.history = Collections.emptyList();
        this.historyComplete = false;

        // This is reused for every pathway opened, so anything left over from the last one is dropped
        // before the new one is read. Without it the model shows as it stood at whichever change was
        // being looked at last time.
        pathwayTreePresenter.clearSelection();

        pathwayTreePresenter.read(pathway);
        showConstraints();
        mutationListPresenter.setData(Collections.emptyList());
        fetchHistory(pathwaysDoc.asDocRef(), pathway.getName());
//        getView().setConstraints(SafeHtmlUtils.EMPTY_SAFE_HTML);
//        getView().setSpans(SafeHtmlUtils.EMPTY_SAFE_HTML);

        getView().setName(pathway.getName());
//        getView().setDetails(new PathwayTreePresenter().build(pathway));
//
//        addNode(pathway.getRoot());
    }

//    private void addNode(final PathNode node) {
//        nodeMap.put(node.getUuid(), node);
//        node.getTargets().forEach(target -> {
//            target.getNodes().forEach(this::addNode);
//        });
//    }

    public Pathway write() {
        String name = getView().getName();
        name = name.trim();

        if (name.isEmpty()) {
            throw new ValidationException("A pathway must have a name");
        }

        final NanoTime now = NanoTime.ofMillis(System.currentTimeMillis());
        return pathway.copy().name(name).updateTime(now).build();
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        final PopupSize popupSize = PopupSize.resizable(1024, 800);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface PathwayEditView extends View, Focus {

        String getName();

        void setName(final String name);

        void setTree(View view);

        void setConstraints(View view);

        void setMutations(View view);
    }
}
