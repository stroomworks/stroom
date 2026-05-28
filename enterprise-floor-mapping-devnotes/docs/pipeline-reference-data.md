---
title: "Pipeline Reference Data"
linkTitle: "Pipeline Reference Data"
description: "How to use Reference Data for enrichment in Stroom pipelines using the stroom:lookup function."
date: 2026-05-20
weight: 21
tags:
  - reference-data
  - lookup
  - enrichment
  - pipeline
---


**Reference Data** in Stroom is used to enrich event data during pipeline processing.
It allows you to perform high-speed lookups against key-value pairs or numerical ranges (like IP address blocks) stored in an internal database (LMDB).


## Overview


Enrichment is the process of adding additional context to an event.
For example, if an event contains a `userId`, you might use reference data to look up the user's `Full Name` or `Department` and add those fields to the resulting event.


### Characteristics


Reference data is:
*   **Time-Sensitive**:
    Lookups (for Off-Heap data) use the event's timestamp to find the "effective" version of the reference data at that point in time.
*   **Reusable**:
    A single reference stream can be used by many different pipelines.


## The Reference Data Pipeline


A reference data pipeline is a specialized pipeline that loads data into the Reference Data Store.
Unlike event pipelines, its goal is not to produce a new stream of files, but to populate an internal index.


### Mandatory Components


A reference pipeline must contain the following elements:


1.  **Parser**:
    Converts raw reference source (CSV, XML, etc.) into XML events.
    For example, use the {{< pipe-elm "DSParser" >}} for CSV data.
1.  {{< pipe-elm "XSLTFilter" >}}:
    Transforms the parsed XML into the specific **Reference Data Schema** (see below).
    The {{< pipe-elm "XSLTFilter" >}} must use the `reference-data:2` namespace in its output.
1.  {{< pipe-elm "ReferenceDataFilter" >}}:
    The final element in the chain.
    It "listens" for the XML produced by the XSLT and writes the keys, maps, and values into the Store.


{{% note %}}
The **ReferenceDataFilter** is a **terminal element**.
It writes data directly to the internal store and does not produce output for further elements.
Consequently, reference pipelines do not require (and typically do not have) a **StreamAppender**.
{{% /note %}}


### Processor Filters and Lazy Loading


You typically **do not** need to create a Processor Filter for a reference data pipeline.
Stroom uses **Lazy Loading**.
When a pipeline performs a lookup, Stroom automatically identifies the required reference stream and triggers a background task to load it if it is not already in the store.


You should only add a Processor Filter if you want to proactively index the data as soon as it is received.
This is useful for very large reference sets that take a long time to load.


### ReferenceDataFilter Properties


The {{< pipe-elm "ReferenceDataFilter" >}} has specific properties that control how data is indexed:


| Property | Default | Description |
| :--- | :--- | :--- |
| **Override Existing Values** | `true` | If `true`, a duplicate key within the same stream will overwrite the previous value. If `false`, the first value is kept. |
| **Warn On Duplicate Keys** | `false` | If `true`, a warning is logged to the pipeline output whenever a duplicate key is encountered in the source. |


Use **Warn On Duplicate Keys** during development or for high-integrity feeds to identify data quality issues in your reference source.


### Reference Data Schema


The XSLT in a reference pipeline must produce XML conforming to the `reference-data:2` schema.


{{% warning %}}
The `<map>` element is **crucial** as it defines the "bucket" or "set" of reference data (the map name) that the record belongs to.
This is **not** set in the Stroom user interface (e.g., it is not a property of the **ReferenceDataFilter**).
It is purely defined in the XML produced by the XSLT of your reference pipeline.
{{% /warning %}}


### Naming convention


The convention is to name the map in UPPER_SNAKE_CASE using INPUT_TO_OUTPUT_MAP.
For example:
- FILENO_TO_LOCATION_MAP
- HOSTNAME_TO_IP_MAP

This is only suggested as a convention; it is not enforced.


#### Key-Value Pair


Used for simple mappings like ID to Name.


```xml
<referenceData xmlns="reference-data:2">
  <reference>
    <map>USER_TO_DEPARTMENT_MAP</map>
    <key>jbloggs</key>
    <value>Engineering</value>
  </reference>
</referenceData>
```


#### Range-Value Pair


Used for mappings where a value applies to a range of numerical keys, such as CIDR blocks.
The `from` and `to` values must be long integers.


```xml
<referenceData xmlns="reference-data:2">
  <reference>
    <map>IP_TO_LOCATION_MAP</map>
    <from>3232235521</from> <!-- 192.168.0.1 -->
    <to>3232235775</to>   <!-- 192.168.0.255 -->
    <value>UK_OFFICE</value>
  </reference>
</referenceData>
```


{{% note %}}
The `<value>` element can contain a simple string or a complex XML fragment (e.g., an entire `<evt:User>` object). [TBC]
{{% /note %}}


## Performing Lookups


To use reference data in an event pipeline, you use the `stroom:lookup()` extension function within an XSLT.


### The stroom:lookup Function


```xslt
stroom:lookup(mapName, key, [time], [ignoreWarnings], [trace])
```


*   **mapName**:
    The name of the map defined in the reference pipeline (e.g., 'UserToDepartment').
*   **key**:
    The key to look up.
*   **time** (Optional):
    The ISO 8601 string or epoch ms.
    Defaults to the event time.
*   **ignoreWarnings** (Optional):
    Boolean.
    If true, suppresses warnings if the key is not found.
*   **trace** (Optional):
    Boolean.
    If true, outputs detailed lookup trace information to the pipeline logs.


### Pipeline References


For `stroom:lookup` to find data, you must link the event pipeline to the reference data:


1.  Open the **Event Pipeline**.
1.  Select the {{< pipe-elm "XSLTFilter" >}} that performs the lookup.
1.  Go to the **Pipeline References** tab.
1.  Add a reference to the **Reference Feed** and specify the **Reference Pipeline**.


## Configuration


Reference data behavior is controlled via the `stroom.pipeline.referenceData` configuration path.


### Storage Strategies


Stroom automatically selects the storage strategy based on the **Stream Type** defined in the Pipeline Reference.
Users do not need to set this manually.


#### Off-Heap (LMDB)


This is used for **External Reference Data** (any Stream Type except `Context`).
It is the most common storage type, used for data that is shared across many event streams and is effective-time sensitive.


*   **Trigger**:
    Added as a Pipeline Reference with Stream Type `Reference`.
*   **Persistence**:
    Data persists across service restarts.
*   **Scale**:
    Can exceed the size of the JVM heap as it is stored on disk (LMDB).


#### On-Heap (Memory)


This is used for **Context Data** (specifically Stream Type `Context`).
Context data is transient reference data that is attached specifically to a single event stream.


*   **Trigger**:
    Added as a Pipeline Reference with Stream Type `Context`.
*   **Persistence**:
    Data is transient and is discarded once the stream has been processed.
*   **Scale**:
    Limited by the available JVM heap space.


### Key Parameters


| Parameter | Default | Description |
| :--- | :--- | :--- |
| `lmdb.maxStoreSize` | `50GiB` | The maximum size of the LMDB database on disk. |
| `purgeAge` | `30 days` | How long to keep reference data after it was last accessed. |
| `lmdb.maxReaders` | `150` | Max concurrent threads that can read from the store. |
| `lmdb.readerBlockedByWriter` | `true` | If true, a write operation blocks all readers (saves disk space). |
| `maxPutsBeforeCommit` | `200,000` | Records to batch before committing during a load. |


### Configuration Guidance


#### Store Size and Location


The `maxStoreSize` must be smaller than the available space on the local disk.
Estimate your total reference data size (keys + values) and set this to at least **2x** that estimate to allow for overhead and internal processing.


{{% warning %}}
The `localDir` for reference data **MUST** be on a local disk (ideally SSD/NVMe).
Never use network storage (NFS/SMB) for the reference data store.
LMDB's memory-mapping will cause severe performance issues or system instability over a network.
{{% /warning %}}


#### Retention (Purge Age)


Reference data is "lazy loaded" into the store when first requested by a pipeline.
The `purgeAge` determines how long data stays in the store after its last use.
If you have stable data and plenty of disk, the default `30 days` is recommended.
Reduce this if you have very limited disk space and many transient reference streams.


#### Performance vs. Disk Space


The `readerBlockedByWriter` setting is a critical trade-off:
*   **`true` (Default)**:
    Lookups will pause briefly while data is being loaded.
    This allows Stroom to reclaim disk space from old/updated records immediately.
*   **`false`**:
    Lookups never block, even during loads.
    However, the database file will grow significantly over time because LMDB cannot reuse "dead" pages while readers are active.
    Only use this if you have extreme performance requirements and ample disk space.


#### Batch Commits


`maxPutsBeforeCommit` controls how often data is committed during a load:
*   For **high throughput** of large single loads, increase this value or set it to `0` (commit at end).
*   If many different reference streams are being loaded **simultaneously**, decrease this (e.g., to `5,000`) to allow the loads to interleave their write operations.


## Example: Site Enrichment


This example demonstrates how to use reference data to map a `siteId` in a log file to a `Site Name` and `Location`.


### 1. The Reference Data


#### Source Data (CSV)


This is the raw data provided to the **Reference Feed**.


```csv
siteId,siteName,location
S01,London HQ,London
S02,New York Branch,New York
```


#### Reference Pipeline Configuration


1.  **DSParser**:
    Uses a Data Splitter to convert the CSV into XML.
1.  **XSLTFilter**:
    Transforms the CSV-XML into the `reference-data:2` format.
1.  **ReferenceDataFilter**:
    Indexes the result into the store.


**Reference XSLT Output:**


```xml
<referenceData xmlns="reference-data:2">
  <reference>
    <map>SITEID_TO_LOCATION_MAP</map>
    <key>S01</key>
    <value>
      <site>
        <name>London HQ</name>
        <location>London</location>
      </site>
    </value>
  </reference>
</referenceData>
```


### 2. The Event Data


#### Input XML (Raw Event)


This is the XML produced by the parser in your **Event Pipeline**.


```xml
<event>
  <time>2026-05-20T10:00:00.000Z</time>
  <user>jbloggs</user>
  <siteId>S01</siteId>
</event>
```


### 3. The Enrichment


#### Event Pipeline Configuration


1.  **XSLTFilter**:
    Performs the lookup.
1.  **Pipeline Reference**:
    Added to the **XSLTFilter**, pointing to the **Reference Feed** containing the site data.


**Enrichment XSLT:**


```xml
<xsl:template match="event">
  <xsl:copy>
    <!-- Copy existing fields -->
    <xsl:apply-templates select="@*|node()"/>


    <!-- Perform the lookup -->
    <xsl:variable name="siteData" select="stroom:lookup('SITEID_TO_LOCATION', siteId)"/>


    <!-- Add enriched fields from the XML fragment in the reference value -->
    <siteName><xsl:value-of select="$siteData/site/name"/></siteName>
    <location><xsl:value-of select="$siteData/site/location"/></location>
  </xsl:copy>
</xsl:template>
```


#### Final Output


The enriched event now contains the data looked up from the reference store.


```xml
<event>
  <time>2026-05-20T10:00:00.000Z</time>
  <user>jbloggs</user>
  <siteId>S01</siteId>
  <siteName>London HQ</siteName>
  <location>London</location>
</event>
```


## Troubleshooting


### Reloading and Refreshing Reference Data


Stroom caches reference data in an internal store (LMDB) to ensure high performance.
**Changing a pipeline's XSLT will not automatically update data already in the store.**


#### How to Force a Refresh


To see XSLT changes reflected in your lookups, you must invalidate the existing stored data.


1.  **Identify the Stream**:
    Open the **Explorer** and locate the **Feed** that provides the reference data.
    Open the feed and click the **Data** tab.
    Select the stream in the top list, then look at the **Info** tab in the **bottom pane**.
    The **Stream Id** field in that list is the Stream ID you need.
1.  **Purge the Store**:
    You must remove the existing entries for that stream so Stroom is forced to re-run the pipeline.
    Go to **Help** > **API Specification** in the main menu to open Swagger, then find the `purgeByStream` endpoint.
    Click the **Try it out** button to enable editing, enter your **Stream Id** (and optionally the **nodeName**), and click **Execute**.
    You can also use a `DELETE` request to `/stroom/v1/refData/purgeByStream/{streamId}?nodeName={nodeName}`.
    For a single-node setup, leave the **nodeName** blank/null.
    For a cluster, provide the name of the node (found in **Monitoring** > **Nodes**).
    To ensure a full refresh in a cluster, repeat the purge for each node name.
1.  **Clear Caches**:
    Clear the following in **Monitoring** > **Cache** (found in the main menu):
    **ReferenceDataCache**: Forces Stroom to re-evaluate which streams are available.
    **XsltPool**: **CRITICAL**.
    Forces Stroom to re-compile your updated XSLT rather than using the old cached version.
1.  **Trigger Reload**:
    Perform a lookup.
    Stroom will detect the missing data, find the source stream, and re-run the pipeline using your updated (and re-compiled) XSLT.


#### Lazy Loading vs. Processing Filters


*   **Lazy Loading**:
    Data is refreshed the next time a lookup is requested after a purge.
*   **Processing Filters**:
    If you use processing filters to load reference data, you must **Delete** the old processing tasks to trigger a re-run of the filter.


### The "NEW" State Error


A common error during lookups is:
`IllegalStateException: Current loader state: NEW, valid states: [INITIALISED, STAGED]`


This usually means the **Reference Pipeline** failed to start.
This is rarely an issue with the lookup itself, but rather a configuration error in the reference pipeline, such as:
*   A syntax error in the reference pipeline's XSLT.
*   The reference pipeline is missing a Parser or a **ReferenceDataFilter**.
*   The user lacks permissions to **Use** the reference pipeline or feed.


---


| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
