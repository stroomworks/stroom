package stroom.visualisation.client.presenter.tree;

/* Copyright 2013 Grant Slender
 * Copyright 2025 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * */

import java.util.ArrayList;

import com.google.gwt.cell.client.Cell;
import com.google.gwt.cell.client.ValueUpdater;
import com.google.gwt.user.cellview.client.CellTree;
import com.google.gwt.user.cellview.client.TreeNode;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.gwt.view.client.TreeViewModel;

public class UpdatableTreeModel implements TreeViewModel {

    ValueUpdater<UpdatableTreeNode> valueUpdater;
    SingleSelectionModel<UpdatableTreeNode> selectionModelCellTree;
    ListDataProvider<UpdatableTreeNode> rootDataProvider;
    int inputBoxSize;
    CellTree tree;
    private final String ignoredCharacters;

    public UpdatableTreeModel(final SingleSelectionModel<UpdatableTreeNode> selectionModelCellTree,
                              final String ignoredCharacters) {
        this(selectionModelCellTree, ignoredCharacters, null, 20);
    }

    public UpdatableTreeModel(final SingleSelectionModel<UpdatableTreeNode> selectionModelCellTree,
                              final String ignoredCharacters,
                              final ValueUpdater<UpdatableTreeNode> valueUpdater,
                              final int inputBoxSize) {
        this.selectionModelCellTree = selectionModelCellTree;
        this.valueUpdater = valueUpdater;
        this.inputBoxSize = inputBoxSize;
        this.rootDataProvider = new ListDataProvider<>(new ArrayList<>());
        if (ignoredCharacters != null) {
            this.ignoredCharacters = ignoredCharacters;
        } else {
            this.ignoredCharacters = CustomEditTextCell.NO_IGNORED_CHARACTERS;
        }
    }

    public ListDataProvider<UpdatableTreeNode> getRootDataProvider() {
        return rootDataProvider;
    }

    public void add(final UpdatableTreeNode parent, final UpdatableTreeNode child) {

        if (parent == null) {
            // root-node
            rootDataProvider.getList().add(child);
        } else {
            parent.addChild(child);
            if (parent.getParent() == null) {
                rootDataProvider.refresh();
            } else {
                parent.getParent().getDataProvider().refresh();
            }
        }
    }

    public static class NodeChildToClose {
        public TreeNode node;
        public int childIndex;
    }

    private NodeChildToClose searchTreeNode(final TreeNode parent,
                                            final UpdatableTreeNode nodeToCheck) {

        final int childCount = parent.getChildCount();
        for (int idx = 0; idx < childCount; idx++) {
            final boolean leaveOpen = parent.isChildOpen(idx);
            final TreeNode node = parent.setChildOpen(idx, true, false);
            if (!leaveOpen)
                parent.setChildOpen(idx, false, false);
            if (node != null) {
                final UpdatableTreeNode utn = (UpdatableTreeNode) node.getValue();
                final NodeChildToClose nctc;
                if (nodeToCheck.getParent() == utn) {
                    nctc = new NodeChildToClose();
                    nctc.node = parent;
                    nctc.childIndex = idx;
                    return nctc;
                } else {
                    if (node.getChildCount() > 0) {
                        nctc = searchTreeNode(node, nodeToCheck);
                        if (nctc != null)
                            return nctc;
                    }
                }
            }
        }
        return null;
    }

    public void remove(final UpdatableTreeNode objToRemove) {
        final UpdatableTreeNode parent = objToRemove.getParent();

        if (parent == null) {
            // root-node
            rootDataProvider.getList().remove(objToRemove);
        } else {
            // find open node and close it when last child is to be removed !!!
            if (objToRemove.getParent().getChildCount() == 1) {
                final NodeChildToClose nctc = searchTreeNode(tree.getRootTreeNode(), objToRemove);
                if (nctc != null)
                    nctc.node.setChildOpen(nctc.childIndex, false);
            }
            parent.removeChild(objToRemove);
            if (parent.getParent() == null) {
                rootDataProvider.refresh();
            } else {
                parent.getParent().getDataProvider().refresh();
            }
        }
        selectionModelCellTree.clear();
    }

    @Override
    public <T> NodeInfo<?> getNodeInfo(final T value) {
        if (value instanceof final UpdatableTreeNode nodeValue) {
            final Cell<UpdatableTreeNode> cell = getCell(inputBoxSize, nodeValue);
            return new DefaultNodeInfo<>(nodeValue.getDataProvider(),
                    cell,
                    selectionModelCellTree,
                    valueUpdater);
        } else {
            return null;
        }
    }

    @Override
    public boolean isLeaf(final Object value) {
        if (value instanceof final UpdatableTreeNode t) {
            return !t.hasChildren();
        }
        return false;
    }

    public void setCellTree(final CellTree cellTree) {
        this.tree = cellTree;
    }

    /**
     * Overridable method to return the cell which will display all values.
     * @param inputBoxSize The size of the input box, as set in the constructor.
     * @param value The node
     * @return A cell to display values. Must not return null.
     */
    protected Cell<UpdatableTreeNode> getCell(final int inputBoxSize, final UpdatableTreeNode value) {
        return new CustomEditTextCell(inputBoxSize, ignoredCharacters);
    }

}