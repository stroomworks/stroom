package stroom.visualisation.client.presenter.tree;

/* Copyright 2013 Grant Slender
 * Copyright 2025 Crown Copyright (adjustment for Stroom UI)
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
 */

import com.google.gwt.view.client.ListDataProvider;

/**
 * Interface for a node in the updatable tree.
 */
public interface UpdatableTreeNode {

    boolean hasChildren();

    int getChildCount();

    void addChild(UpdatableTreeNode child);

    void removeChild(UpdatableTreeNode child);

    UpdatableTreeNode getParent();

    void setParent(UpdatableTreeNode parent);

    String getLabel();

    void setLabel(String label);

    ListDataProvider<UpdatableTreeNode> getDataProvider();

    /** Used to identify whether a node is a directory or a file */
    boolean isLeaf();

    /**
     * Used to determine whether the given label already exists within this directory.
     */
    boolean labelExists(String label);

}