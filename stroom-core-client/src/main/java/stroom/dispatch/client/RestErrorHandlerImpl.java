package stroom.dispatch.client;

public class RestErrorHandlerImpl implements RestErrorHandler {

    @Override
    public void onError(RestError error) {
        if (error != null) {
            com.google.gwt.user.client.Window.alert("REST Error: " + error.getMessage());
        }
    }
}
