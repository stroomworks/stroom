---
title: "Dashboards"
description: "An overview of how dashboards query, aggregate, and visualise data in Stroom."
date: 2026-05-13
weight: 35
tags:
  - dashboard
  - visualisation
  - query
---


Stroom Dashboards provide an interactive, web-based interface for querying, aggregating, and visualising data stored within Stroom.
They are composed of various components such as queries, tables, and visualisations that work together to provide insights into your data.


## Data Sources


Dashboards can query several types of data sources:


*   **Index**: The primary data source, containing searchable data sharded across the cluster.
*   **View**: A virtual data source that can combine multiple indexes or apply a fixed set of criteria.
*   **Statistic**: Specialized for time-series data, often used for performance monitoring.
*   **Plan B**: A high-speed data store used for specific types of analytic data.


## Data Flow and Processing


The flow of data through a dashboard follows a specific lifecycle:


1.  **Search Request**:
    When a dashboard is opened or refreshed, it sends a `SearchRequest` to the server.
    This request contains the query expression (e.g., `where feed = "MY_FEED"`) and the configuration for all tables and visualisations.
2.  **Data Retrieval**:
    The Stroom search engine retrieves matching records from the underlying data source.
3.  **Aggregation & Transformation**:
    *   Data is passed through an **Extraction Pipeline** (if configured) to parse and format fields.
    *   The **Table Component** aggregates this data based on its field definitions.
    *   Fields can use **Expressions** (Stroom's built-in function language) to transform data (e.g., `floorDay(${timestamp})` or `count()`).
    *   **Grouping** determines how data is collapsed into rows.
    Nested groups create a hierarchical data structure.
4.  **Result Generation**:
    The server generates a `Result` object (often a `FlatResult` or a hierarchical `Row` structure) which is sent back to the browser.
5.  **UI Rendering**:
    *   The **Table Component** renders the aggregated data in a grid.
    *   The **Visualisation Component** receives the same aggregated data but interprets it according to its specific mappings.


## Relationship Between Tables and Visualisations


A key architectural detail of Stroom dashboards is that **Visualisations cannot exist independently**.
Every visualisation must be linked to a specific **Table** component.


### Why a Table is Required


The Table component is responsible for defining how data is aggregated and grouped.
When a search is performed, the server calculates the results specifically for that table's structure.
The visualisation then "consumes" the data produced by its parent table.


### Hiding the Table


If you want to display only a visualisation without the corresponding data grid, you can hide the table:


1.  Add both a **Table** and a **Visualisation** to your dashboard.
2.  Configure the visualisation to use the table as its data source.
3.  In the table's settings, uncheck the **Visible** property (found in the tab or layout settings).


This configuration allows the table to continue acting as the data aggregator in the background while the UI only displays the resulting chart.


## Visualisation Integration


Visualisations in Stroom are hosted in an isolated `iframe` for security and stability.


1.  **Bridge Initiation**:
    The dashboard UI initiates a communication bridge with the iframe via the HTML5 `postMessage` API.
1.  **Script Injection**:
    Stroom injects the required JavaScript libraries (e.g., D3.js) and the specific visualisation script into the iframe.
1.  **Data Delivery**:
    The dashboard calls the `setData(context, settings, data)` method on the visualisation object within the iframe.
    *   **Context**: Contains information about the current environment (e.g., theme, time zone).
    *   **Settings**: Contains the user-defined mappings from table columns to visualisation parameters (e.g., mapping `timestamp` to the `x` axis).
    *   **Data**: The actual aggregated result set from the server.


## Interactivity


Dashboards are highly interactive:


*   **Selections**:
    Clicking a row in a table or an element in a visualisation can trigger "selection" events.
    These events can be used to filter other components in the same dashboard.
    For example, clicking a feed name in a chart filters a table to show only that feed's data.
*   **Parameters**:
    Dashboards can define parameters (e.g., `${userId}`) that are substituted into queries and expressions at runtime, allowing for reusable dashboard templates.


---


| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
