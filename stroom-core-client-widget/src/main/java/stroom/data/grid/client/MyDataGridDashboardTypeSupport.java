package stroom.data.grid.client;

import stroom.widget.menu.client.presenter.Item;

public interface MyDataGridDashboardTypeSupport<R> {

    Item createContextMenu(int rowIndex, int colIndex);
}
