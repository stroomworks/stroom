/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.dispatch.client;

import stroom.task.client.SimpleTask;
import stroom.task.client.Task;
import stroom.task.client.TaskMonitor;
import stroom.task.client.TaskMonitorFactory;
import stroom.util.shared.PropertyMap;
import stroom.util.shared.ResourceKey;

import com.google.gwt.user.client.ui.FormPanel.SubmitCompleteEvent;
import com.google.gwt.user.client.ui.FormPanel.SubmitCompleteHandler;
import com.google.gwt.user.client.ui.FormPanel.SubmitEvent;
import com.google.gwt.user.client.ui.FormPanel.SubmitHandler;

public abstract class AbstractSubmitCompleteHandler implements SubmitHandler, SubmitCompleteHandler {

    private final TaskMonitorFactory taskMonitorFactory;
    private final Task task;
    private TaskMonitor taskMonitor;

    public AbstractSubmitCompleteHandler(final String taskName,
                                         final TaskMonitorFactory taskMonitorFactory) {
        this.task = new SimpleTask(taskName);
        this.taskMonitorFactory = taskMonitorFactory;
    }

    @Override
    public void onSubmit(final SubmitEvent event) {
        taskMonitor = taskMonitorFactory.createTaskMonitor();
        taskMonitor.onStart(task);
    }

    @Override
    public void onSubmitComplete(final SubmitCompleteEvent event) {
        try {
            final String result = event.getResults();
            if (result == null || result.trim().isEmpty()) {
                // A form upload that comes back with no response body almost always means
                // the request never reached the servlet — e.g. it was rejected by a filter
                // (HTTP 403), the session expired, or the file exceeded the server's size
                // limit. There is no server-supplied detail to show, so explain the likely
                // causes rather than opening an empty error dialog.
                onFailure("The file could not be uploaded because the server returned no "
                        + "response. This usually means the request was rejected (you may not "
                        + "have permission, or your session may have expired) or the file was "
                        + "too large.");
            } else {
                try {
                    final PropertyMap propertyMap = new PropertyMap();
                    propertyMap.loadArgLine(result);

                    if (propertyMap.isSuccess()) {
                        onSuccess(new ResourceKey(propertyMap));
                    } else {
                        onFailure(orDefault(propertyMap.get("exception"),
                                "The file upload failed but the server did not report why."));
                    }
                } catch (final RuntimeException e) {
                    onFailure(orDefault(e.getMessage(),
                            "The upload response could not be read: " + e.getClass().getSimpleName()));
                }
            }
        } finally {
            taskMonitor.onEnd(task);
        }
    }

    /** Returns {@code value} if it has non-blank content, otherwise {@code fallback}. */
    private static String orDefault(final String value, final String fallback) {
        return value != null && !value.trim().isEmpty()
                ? value
                : fallback;
    }

    protected abstract void onSuccess(ResourceKey resourceKey);

    protected abstract void onFailure(String message);
}
