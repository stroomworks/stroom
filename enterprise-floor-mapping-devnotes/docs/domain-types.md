---
title: "Domain Types"
description: "How to use Domain Types to enable context-sensitive navigation between dashboards."
date: 2026-05-13
weight: 45
tags:
  - domain-type
  - dashboard
  - navigation
---


Domain Types are a mechanism in Stroom for enabling context-sensitive navigation between Dashboards.
They allow you to define semantic "types" for data fields and link those types to specific Dashboards that can handle them.


## Overview


A Domain Type is a string identifier, typically following a `Class.Attribute` pattern (e.g., `Host.IP`, `User.ID`, `File.Hash`).
Wildcards can also be used, such as `Host.*` or `*.IP`.


The primary purpose of Domain Types is to enable "Jump to" functionality.
When a column in a dashboard table has a Domain Type assigned to it, right-clicking a cell in that column provides a menu of other Dashboards that are registered to handle that specific Domain Type.


## How it Works


### Defining Domain Types


Domain Types can be grouped into **Domain Type Documents** in the Explorer tree.
This allows for a central registry of semantic types used across the system.


### Tagging Data Fields


In a **Lucene Index** or other data source definition, you can assign a Domain Type to specific fields.
For example, you might tag a field named `client_ip` with the Domain Type `Host.IP`.


### Registering Dashboards


In the **Dashboard** settings, you can list the Domain Types that the dashboard is designed to display or analyze.
A "Host Investigation" dashboard would be registered with the `Host.IP` domain type.


### Navigating (The "Jump to" Menu)


When viewing a dashboard:


1.  If you right-click a cell whose column has an associated Domain Type (e.g., `Host.IP` for the value `192.168.1.50`).
1.  Stroom identifies all other Dashboards registered with that same Domain Type.
1.  These dashboards appear in the "Jump to" context menu.
1.  Selecting a dashboard will open it in a new tab, automatically passing the cell value as a parameter.


## Parameter Passing


When "jumping" to a destination dashboard, Stroom automatically passes the following context:


*   **The Value**:
    The value of the cell that was clicked is passed as a parameter named after the Domain Type.
    The name is sanitized to be a valid parameter name (e.g., `Host_IP=192.168.1.50`).
*   **Time Range**:
    The current time range of the source dashboard is passed as `timeRange.from` and `timeRange.to` parameters.


The destination dashboard must be configured to use these parameters in its query or filter expressions (e.g., `where ip = ${Host_IP}`).


## Benefits


*   **Seamless Pivoting**:
    Allows analysts to quickly move from a high-level overview to a detailed investigation of a specific entity (user, host, file) without manual searching.
*   **Context Preservation**:
    Maintains the temporal context (time range) across different views of the data.
*   **Semantic Decoupling**:
    Dashboards only need to know about the "Type" they handle, not which specific indexes or tables the data comes from.


---

| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
