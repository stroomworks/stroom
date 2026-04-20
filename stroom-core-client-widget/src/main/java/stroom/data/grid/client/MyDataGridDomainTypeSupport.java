package stroom.data.grid.client;

import stroom.widget.menu.client.presenter.Item;

public interface MyDataGridDomainTypeSupport<R> {

    Item createContextMenu(int rowIndex, int colIndex);
}
