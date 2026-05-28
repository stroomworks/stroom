---
title: "Pipeline Data Destinations"
linkTitle: "Pipeline Data Destinations"
weight: 20
description: >
  Where data goes after it has been processed by a pipeline.
tags:
  - pipeline
  - indexing
  - planb
---


Once a pipeline has finished transforming data, the results must be stored or sent to another system.
This is handled by **Appenders** and specialized **Filters**.


## The Stroom Data Store (Stream Store)


The most common destination for pipeline output is back into Stroom's own data store.
This allows for multi-stage processing.
For example, Normalization -> Enrichment -> Indexing.


*   {{< pipe-elm "StreamAppender" >}}:
    Writes the output as a new stream.
    You must specify:
    *   **Feed**: Which feed the new data belongs to.
    *   **Stream Type**: Typically `Events` (for processed data) or `Context`.
*   {{< pipe-elm "RollingStreamAppender" >}}:
    Used for high-volume output.
    It pools data and only creates a new stream in the database when a certain size or time threshold is met.
    This reduces database overhead.


## Search Indexes


Data often needs to be made searchable.
This is achieved by sending XML events into an indexing element.


*   {{< pipe-elm "IndexingFilter" >}}:
    Sends data to a standard Stroom Lucene index.
    The XSLT must produce XML that matches the index's field structure.
*   {{< pipe-elm "ElasticIndexingFilter" >}}:
    Sends data to an external Elasticsearch cluster.
    Like the Lucene filter, it requires specific XML field mappings.


## Plan B (State Store)


**Plan B** {{< stroom-icon "document/PlanB.svg" >}} is Stroom's high-performance state management system.
It is used for storing "state" that can be looked up by other pipelines.
For example, "What was the last known IP for this user?".


*   {{< pipe-elm "PlanBFilter" >}}:
    Instead of writing to a stream or index, this filter writes data into an **LMDB (Lightning Memory-Mapped Database)** shard.
*   **How it works**:
    1.  The pipeline processes data (often with a `Reference Data` stream type).
    1.  The {{< pipe-elm "PlanBFilter" >}} at the end of the pipe populates an LMDB map (State, Temporal State, Session, etc.).
    1.  Once the pipeline completes, the shard is uploaded to storage nodes for central merging.
*   **Usage**:
    Other pipelines can then perform ultra-fast lookups against these Plan B maps using the `stroom:lookup()` XSLT function or the `getState()` Dashboard function.


## External Systems


Pipelines can also "push" data out of Stroom entirely:


*   {{< pipe-elm "HTTPAppender" >}}:
    Posts the processed data to a remote URL via HTTP or HTTPS.
    This is useful for forwarding alerts to a SOC or a third-party ticketing system.
*   {{< pipe-elm "FileAppender" >}}:
    Writes the output directly to the local file system of the Stroom node.
*   {{< pipe-elm "HDFSFileAppender" >}}:
    Writes data to a Hadoop Distributed File System.


## Summary of Destinations


| Destination | Primary Use Case | Element Type |
| :--- | :--- | :--- |
| **Stream Store** | Long-term storage, further processing. | `StreamAppender` |
| **Lucene/Elastic** | Fast keyword and structured search. | `IndexingFilter` |
| **Plan B** | High-speed state/reference lookups. | `PlanBFilter` |
| **Remote API** | Real-time alerting or external integration. | `HTTPAppender` |
| **Local Disk** | Debugging or manual data export. | `FileAppender` |


---

| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
