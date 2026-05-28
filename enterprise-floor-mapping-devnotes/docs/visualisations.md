---
title: "Visualisations"
description: "A guide to the standard visualisations available in Stroom and their data formats."
date: 2026-05-13
weight: 40
tags:
  - visualisation
  - dashboard
---


Visualisations in Stroom are modular JavaScript components that use the D3.js library to render interactive charts.
They are configured via **Visualisation** documents, which define the scripts to load and the settings available to the user.


Standard visualisations are typically provided by the **`stroom-visualisations`** content pack.


## Common Data Example


To illustrate the configuration of each visualisation, we will use a common set of "System Resource Usage" data.
The examples below assume your pipeline produces XML in the `records:2` format as follows:


```xml
<records xmlns="records:2">
  <record>
    <data name="Timestamp" value="2026-05-13T10:00:00.000Z"/>
    <data name="Host" value="server-01"/>
    <data name="Component" value="CPU"/>
    <data name="Usage" value="45"/>
    <data name="Status" value="OK"/>
  </record>
  <record>
    <data name="Timestamp" value="2026-05-13T10:01:00.000Z"/>
    <data name="Host" value="server-01"/>
    <data name="Component" value="CPU"/>
    <data name="Usage" value="95"/>
    <data name="Status" value="FATAL"/>
  </record>
</records>
```


## Architecture and Data Format


Every visualisation must implement a standard JavaScript interface and receive data from a linked **Table** component.
The Table defines the aggregation, and the Visualisation settings map specific table columns to chart parameters.
For more information on the data bridge, see [dashboards]({{< relref "dashboards.md" >}}).


## Standard Visualisations


### Line Chart


Designed for time-series or trend analysis.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **x** | `Timestamp` (Sorted ASC) |
| **y** | `Usage` |
| **lineSeries** | `Host` (Creates one line per server) |
| **gridSeries** | `Component` (Creates a grid of charts for CPU, Memory, etc.) |


**Use Case**: Monitoring CPU usage over time for multiple servers.


### Bar Chart


Used for comparing categories.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **x** | `Host` |
| **y** | `Usage` (e.g., using `mean(${Usage})`) |
| **series** | `Component` (Creates grouped or stacked bars) |


**Use Case**: Comparing the average resource usage across different hosts.


### Bubble Chart


Visualises three dimensions of data using X, Y, and size.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **name** | `Host` |
| **value** | `Usage` (Determines bubble size) |
| **series** | `Status` (Colors bubbles by OK/WARN/FATAL) |


**Use Case**: Identifying which servers have the highest usage and their current health status in a single view.


### Doughnut and Pie Charts


Shows proportional data.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **names** | `Status` |
| **values** | `count()` (The number of records in each status) |


**Use Case**: Visualising the overall distribution of system health (e.g., % of servers in "OK" vs "WARN").


### Heat Maps


Effective for identifying patterns and hotspots over time.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **x** | `hourOfDay(${Timestamp})` |
| **y** | `dayOfWeek(${Timestamp})` |
| **values** | `max(${Usage})` |


**Use Case**: Identifying at what time of day and day of the week resource usage typically peaks.


### Traffic Lights and RAG Status


Displays a status indicator based on numeric thresholds.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **field** | `Usage` |
| **RedHi** | `100` |
| **RedLo** | `90` |
| **AmberHi** | `89` |
| **AmberLo** | `70` |
| **GreenHi** | `69` |
| **GreenLo** | `0` |


**Use Case**: A high-level dashboard showing a simple Red/Amber/Green light for a critical system component.


### Text Value


Displays a single aggregated value as large text.


| Parameter | Table Column / Mapping |
| :--- | :--- |
| **field** | `count(${Host})` |


**Use Case**: Displaying a "Total Active Servers" count at the top of a dashboard.


## Developing Custom Visualisations


The code for these visualisations is typically maintained in the `stroom-visualisations-dev` repository.
Each visualisation consists of:


1.  **JavaScript Logic**: The D3.js implementation.
1.  **Settings (JSON)**: A schema defining the controls (tabs, dropdowns, field selectors) visible in the Stroom UI.
1.  **CSS**: Styling specific to the visualisation.


---


| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
