---
title: "Pipeline Data Sources"
linkTitle: "Pipeline Data Sources"
weight: 15
description: >
  Understanding how data enters a pipeline from the Stroom data store.
tags: 
  - processing
  - feed
---


In Stroom, data doesn't just "arrive" at a pipeline.
Instead, pipelines are attached to streams of data that have already been stored in the system.
This document explains the relationship between the Stroom Data Store, Processors, and the Pipeline Source.


## The Entry Point: The `Source` Element


Every pipeline begins with a {{< pipe-elm "Source" >}} element.
This is a special, non-removable element that acts as the gateway.
It handles the low-level details of:


1.  Opening the data stream from the underlying storage.
1.  Decompressing the data if necessary (e.g., ZIP or GZIP).
1.  Providing the raw byte stream to the first "Reader" or "Parser" in your pipeline.


## How Pipelines are Triggered


Data is fed into a pipeline via a **Processor Filter**.
This is the "engine" that drives the work:


1.  **Definition**:
    You create a Processor Filter on a Pipeline and define a set of criteria (e.g., "All streams for Feed 'MY_FEED' with type 'Raw Events'").
1.  **Task Creation**:
    The system constantly scans the data store for new streams matching those criteria.
    When it finds one, it creates a **Processor Task**.
1.  **Execution**:
    A worker thread picks up the task, initializes the pipeline, and points the `Source` element to that specific stream.


## Types of Input Data


When configuring your pipeline source via a Processor Filter, you typically deal with three main categories of data:


### Raw Events


This is the "virgin" data as it was received from the source system (e.g., via Stroom Proxy).
It is usually unstructured text, CSV, or raw JSON.
Pipelines processing this data typically use a {{< pipe-elm "DSParser" >}} to convert it into XML.


### Events (Processed Data)


This is data that has already passed through an initial normalization pipeline.
It is already in XML format (usually conforming to the Event-Logging schema).
Pipelines processing this data often skip the Parser and use an {{< pipe-elm "XMLParser" >}}.


### Context Data


Context data is "supplementary" data sent alongside a main event stream.
It often contains information about the environment (e.g., hostname, IP mapping).
A pipeline can be configured to read this context data to enrich the main event stream using XSLT lookups.


## The Meta Store Connection


Every stream in Stroom consists of two parts:


*   **The Data**:
    The actual content (bytes).
*   **The Meta Data**:
    Attributes about the data (Feed Name, Stream Type, Creation Time, Received Host, etc.).


The **Processor Filter** uses the Meta Data to decide which streams should be processed.
When the pipeline runs, the {{< pipe-elm "Source" >}} element provides both the raw data and access to these Meta Data attributes.
These can be used as "Header" variables in your XSLT transformations.


## Key Properties of Input Streams


When troubleshooting why data isn't being fed into your pipeline, check these meta attributes:


*   **Status**:
    Only streams with a status of `Locked` (while being written) or `Unlocked` (ready for use) can be processed.
    `Deleted` or `Hidden` streams are ignored.
*   **Stream Type**:
    Ensure your Processor Filter is looking for the correct type (e.g., `Raw Events` vs `Raw Reference`).
*   **Feed**:
    The pipeline will only process data associated with the specific Feed(s) defined in the filter.


## Debugging with Stepping Mode


The **Stepper** is Stroom's most powerful debugging feature.
Unlike traditional logs, it allows you to visualize the data transformation as it happens, one record at a time.


### How to Start Stepping


1.  Navigate to a {{< stroom-doc "Feed" >}} or the **Stream Store**.
1.  Select a stream that you want to test your pipeline against.
1.  Click the **Step** {{< stroom-icon "step.svg" >}} button (represented by an icon or found in the context menu).
1.  Choose the {{< stroom-doc "Pipeline" >}} you want to debug.


### What the Stepper Shows


The UI will split into multiple panes, each representing an element in your pipeline (e.g., Source, Parser, XSLTFilter, XMLWriter).


*   **Input vs. Output**:
    You can click on any element to see exactly what data it received and what data it produced.
*   **Navigation**:
    You can move forward or backward through the stream record-by-record using the arrow buttons.
*   **Finding Errors**:
    If an XSLT transformation fails on record #502, you can jump to that specific record and see the input XML that caused the crash.
*   **Markers**:
    Fatal errors and warnings are highlighted with red and yellow markers in the margin, allowing you to quickly identify "bad" data.


### Use Cases for Stepping


*   **Developing XSLT**:
    See the immediate effect of your stylesheet changes on real data.
*   **Fixing Parsers**:
    Identify why a specific log line isn't being split into the correct XML fields.
*   **Validating Lookups**:
    Verify that reference data lookups are returning the expected values.


## How to Tell if it Worked


Once you've configured your Processor Filter and enabled the Pipeline, you can monitor its progress through several screens in the Stroom UI:


### Processor Filter Status


When you look at the **Processor** tab of a Pipeline, you will notice two levels of "Enabled" state:


1.  **The Processor**:
    This is the top-level parent (shown in the top pane).
    Enabling/Disabling this affects all filters associated with the pipeline.
1.  **The Filter**:
    This is the specific set of criteria (shown in the bottom pane, e.g., Feed = X).


{{% note %}}
Both the Processor (parent) and the Filter (child) must be checked for data to start flowing.
{{% /note %}}


This design allows you to pause all processing for a pipeline globally while keeping specific filter configurations ready.
You can also pause just one specific feed's filter without stopping others.


*   **Progress**:
    The progress bar and stream counts will show you how many streams have been processed vs. how many are remaining.
*   **Last Poll**:
    Tells you when the system last searched for new data to process.


### The Task Manager


Open **Monitoring -> Tasks** to see real-time pipeline execution.
If a pipeline is currently running, you will see a `PipelineProcessor` task here.
This is useful for seeing if a pipeline is "stuck" or taking an unusually long time.


### Data Tracking


The most definitive way to verify success is to look at the **Output Feed**.


1.  Navigate to the feed specified in your `StreamAppender`.
1.  Check the **Data** tab.
    You should see new streams with the timestamp and stream type you configured.
1.  Click on a stream to view its content and ensure the transformation (XML, JSON, etc.) looks correct.


## What to do if it Fails


If your pipeline isn't producing output or is behaving unexpectedly, follow these steps:


### Check for Errors (Stream Status)


If a stream fails during processing, it will often be marked as **Error** in the stream store.


1.  Go to **Monitoring -> Streams**.
1.  Look for streams with a status of `Error`.
1.  Select the stream and look at the **Attributes** or **Logs**.
    Stroom often attaches an "Error Stream" to the failed task containing the specific SAX or XSLT error message.


### Common Issues


*   **No Streams Processed**:
    Check that your Processor Filter's criteria (Feed, Stream Type, Status) exactly match the meta-data of your source data.
    Ensure the filter is `Enabled`.
*   **Empty Output**:
    This often means your XSLT didn't match any elements in the input XML.
    Check your XSLT logic and namespaces.
*   **Fatal Errors**:
    These often relate to system issues like "Disk Full" on an appender or "Network Timeout" on an HTTP appender.


{{% note %}}
If a Processor Filter has already scanned all available data and found nothing, it may not automatically pick up "old" data that existed before the filter was created.
In some cases, you may need to **Delete and Recreate** the filter to force a fresh scan of the data store from the beginning.
{{% /note %}}


---

| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
