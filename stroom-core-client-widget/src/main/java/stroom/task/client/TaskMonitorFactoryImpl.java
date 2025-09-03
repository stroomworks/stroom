package stroom.task.client;

public class TaskMonitorFactoryImpl implements TaskMonitorFactory {
    @Override
    public TaskMonitor createTaskMonitor() {
        return new TaskMonitor() {
            @Override
            public void onStart(final Task task) {
            }

            @Override
            public void onEnd(final Task task) {
            }
        };
    }
}
