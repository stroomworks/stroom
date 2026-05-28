---
title: "Feeds"
description: "Understanding Feeds in Stroom: the logical containers for data streams."
date: 2026-05-13
weight: 4
tags:
  - feed
  - data-source
---


In Stroom, a **Feed** is a logical container and identifier for a stream of related data.
It acts as the "label" that follows data from the moment it is received until it is finally processed and indexed.


## What is a Feed?


A feed is not a physical storage location (that is the Stream Store), but a logical grouping.
Every piece of data that enters Stroom must be assigned to exactly one feed.
This assignment allows Stroom to:


1.  **Route Data**: Processor filters use feed names to decide which pipelines should process which data.
2.  **Manage Security**: Permissions can be set at the feed level to control who can view or process specific datasets.
3.  **Define Retention**: You can set different data retention policies for different feeds.


## The Feed UI Page


When you open a Feed document, the UI displays several tabs providing different views of the data and its configuration:


### Settings Tab


Contains the metadata and behavioral configuration for the feed:


*   **Classification**:
    The protective marking or classification of the data (e.g., OFFICIAL-SENSITIVE).
*   **Reference Feed**:
    If checked, this feed is intended to be used as reference data (e.g., IP to Host mappings).
*   **Received Type**:
    The default stream type assigned to data as it arrives (usually `Raw Events`).
*   **Encoding**:
    The character encoding of the incoming data (e.g., UTF-8).
*   **Data Format**:
    The structure of the main data part (e.g., XML, JSON, CSV).
*   **Context Format**:
    The structure of the "context" part of the stream.
    Context data is supplementary information (like environment details) sent alongside the main events.
*   **Data Schema / Version**:
    The name and version of the XML schema (XSD) that the data is expected to conform to.
    This is often used by pipelines to perform automated validation.
*   **Volume Group**:
    Determines the group of physical disk volumes where this feed's data will be stored.
    This allows administrators to place high-priority feeds on faster storage.


### Data Tab


This is the primary diagnostic view for the feed.
It is divided into three horizontal sections:


1.  **Stream List (Top)**:
    Shows all data streams belonging to this feed that match your current filter.
1.  **Stream Relation List (Middle)**:
    Shows the lineage (ancestry) of the selected stream.
    If you select an "Events" stream, this list will show the "Raw Events" it was created from.
1.  **Data Pane (Bottom)**:
    Displays the actual content of the selected stream (Hex, Text, or XML).


### Active Tasks Tab


Shows a real-time list of all background **Processor Tasks** currently running for this feed.


## Naming Conventions


Stroom does not enforce a specific naming convention for feeds, but a common best practice is to use a hierarchical, upper-case pattern:


**`SYSTEM-SUBSYSTEM-DATATYPE`**


Examples:
*   `FIREWALL-CISCO-TRAFFIC`
*   `WINDOWS-OS-SECURITY`
*   `APP-WEBSERVER-ACCESS`


Consistency in naming makes it much easier to create broad **Processor Filters** using wildcards (e.g., `FIREWALL-*`).


## Stream Types and Their Uses


A feed configuration references several "Types".
These types are used by the system to categorize data at different stages of its lifecycle:


*   **Received Type**:
    Usually set to `Raw Events`.
    This is the format the data is in when it first hits the Stroom Datafeed.
*   **Output Types**:
    When a pipeline processes data, it writes to a new stream type (e.g., `Events` or `Context`).
*   **Reference Data Types**:
    Reference feeds often use types like `Raw Reference` or `Reference`.


### Where Types are Referenced


Types are primarily used in **Processor Filters**.
When you create a filter to trigger a pipeline, you specify both the **Feed** and the **Stream Type** (e.g., "Process all `Raw Events` for feed `MY_FEED`").


## Getting Data into a Feed


There are three mechanisms for sending data to a Stroom feed:


### Upload within the UI

You can upload a file within the Stroom UI by using the Upload {{< stroom-icon "upload.svg" >}} button within a feed. 

You will be asked for the following information:

#### Meta Data

Any metadata key:value pairs required. See below for more information.

#### Type

The type of this data within the stream. See above for more information.

#### Effective Date

The Effective Date is stored as the `EffectiveTime` attribute on the resulting stream. 
Its primary use is for **Reference Data** lookups:

##### Lineage Selection

  * When a pipeline performs a lookup against a reference feed, Stroom uses the event time of the record being processed to find the most appropriate reference stream.
  * **Latest-not-after Logic**: Stroom will select the reference stream whose `EffectiveTime` is the latest possible value that is still **less than or equal to** the event time.   

If a feed is not used for reference data, the Effective Date is purely informational and defaults to the creation time of the stream if not provided. 
However, it can still be used as a filter criterion in search expressions.


### Direct HTTP POST (The Datafeed)


Data can be posted directly to the Stroom `datafeed` endpoint using standard HTTP tools like `curl`.


*   **Endpoint**: `https://<stroom-host>/stroom/noauth/datafeed` (or `/stroom/datafeed` for authenticated requests).
*   **Method**: `POST`
*   **Body**: The raw bytes of the data.


### Stroom Proxy


For high-volume production environments, **Stroom Proxy** is used.
Data is sent to the Proxy, which aggregates many small files into larger ZIP archives before forwarding them to the main Stroom cluster.
This significantly improves ingest performance and resilience.


## Supplying Metadata


Metadata (attributes about the data) is just as important as the data itself.
It is supplied alongside the data during ingestion.


### Via HTTP Headers (Direct POST)


When using the direct Datafeed endpoint, metadata is supplied as HTTP headers.
Common headers include:


*   `Feed`: The name of the destination feed (Required).
*   `System`: The source system name.
*   `Environment`: The environment (e.g., PROD, DEV).
*   `Device`: The hostname or IP of the source device.


Example:
```bash
curl -X POST -H "Feed: MY_FEED" -H "System: FIREWALL" --data-binary @logs.txt https://stroom/stroom/noauth/datafeed
```


### Via Meta Files (Stroom Proxy)


When sending data to a Proxy via a file-drop or script, you supply a `.meta` file alongside your data file.
The meta file is a simple text file containing `Key:Value` pairs.


**`my-data.log`** (The data)
**`my-data.log.meta`** (The metadata):
```text
Feed:MY_FEED
System:FIREWALL
Environment:PROD
```

Stroom Proxy will read these pairs and attach them to the stream in the Stroom Meta Store.
These attributes can then be used in **Processor Filters** or accessed as "Header" variables in your **XSLT** transformations.



# The Stepper: Seeing Inside the Pipe


Because [pipelines]({{< relref "pipelines" >}}) can be complex, Stroom provides a tool called the **Stepper** {{< stroom-icon "step.svg" >}} within a feed.
It allows you to "step" through the data record by record.
You can see the input and output of every single element in the pipeline simultaneously.
This makes it easy to identify exactly where a transformation might be failing.


---


| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
