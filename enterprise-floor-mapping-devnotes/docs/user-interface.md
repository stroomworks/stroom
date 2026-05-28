---
title: "User Interface"
description: "An overview of the Stroom web interface, its layout, and core navigation components."
date: 2026-05-13
weight: 3
tags:
  - navigation
---


The Stroom web interface is designed for high-volume data management and complex pipeline development.
It uses a tabbed environment that allows you to work on multiple documents (Pipelines, Indexes, Dashboards) simultaneously.


## Overall Layout


The interface is divided into three primary regions:


1.  **Main Menu (Top)**:
    Provides access to global tools, monitoring views, and security settings.
1.  **Explorer Tree (Left Sidebar)**:
    The primary navigation component used to manage all documents and their hierarchical structure.
1.  **Main Content Area (Center)**:
    The space where document editors, data views, and monitoring screens are displayed in tabs.


## The Explorer Tree


The Explorer Tree is a hierarchical view of all documents stored in Stroom.
It functions similarly to a file system, allowing you to group related documents into folders.


### Custom Structure


The structure of the Explorer Tree is entirely up to you.
Stroom does not enforce a specific layout for where documents must be placed.
You might choose to group documents by project, by data source, or by environment (e.g., Dev, Test, Prod).


### Security and Visibility


The primary implication of the Explorer structure is **Security**.
Stroom uses the tree hierarchy to manage document visibility and permissions.


*   **Inheritance**:
    Permissions set on a folder are typically inherited by all documents and sub-folders within it.
*   **Access Control**:
    By organizing documents into restricted folders, you can easily control which users or groups can see or modify specific parts of the system.


## Folders as Aggregated Views


While folders are used for organization, they also provide a powerful **aggregated view** of the documents they contain.
When you open a folder in the main content area, it displays several tabs:


*   **Data**:
    Shows a combined **Stream List** for all Feeds located within that folder (including its sub-folders).
*   **Processors**:
    Lists all **Processor Filters** defined for any pipeline in that folder tree.
    This allows you to enable or disable processing for an entire project from one screen.
*   **Active Tasks**:
    Shows all currently running or pending **Processor Tasks** for the pipelines and feeds within that folder.
*   **Permissions**:
    Allows you to manage the access control list (ACL) for the folder.


## Main Menu Overview


The main menu provides access to system-wide functionality, grouped into several categories:


### Monitoring


This menu is essential for administrators and developers to track system health and processing progress.


*   **Data (Streams)**:
    The primary view for searching and inspecting all data stored in Stroom.
*   **Task Manager**:
    Shows real-time execution of all background tasks across the cluster.
*   **Jobs**:
    Allows management of scheduled system jobs (e.g., cleanup, merging).
*   **Nodes**:
    Provides health and performance statistics for every node in the Stroom cluster.


### Tools


Contains utilities for managing content and configurations.


*   **Content Store**:
    Allows you to install and upgrade content packs (schemas, standard visualisations, etc.).
*   **Import/Export Config**:
    Used to move Stroom configurations between different environments.
*   **Dependencies**:
    Visualizes the relationships and dependencies between different Stroom documents.


### Security


Used for managing access to the system.


*   **Users / Groups**:
    Manage user accounts and their memberships.
*   **App Permissions**:
    Define high-level system permissions (e.g., "Manage Users" or "View Data").
*   **API Keys**:
    Manage tokens for external systems to interact with Stroom APIs.


---


| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
