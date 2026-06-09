package stroom.query.client.presenter;

import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

public interface QueryDataView extends View, HasUiHandlers<QueryDataUiHandlers> {
    void setQuery(String query);

    String getQuery();

    void setTable(View view);
}
