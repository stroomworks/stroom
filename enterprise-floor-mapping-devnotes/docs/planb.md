---
title: "Plan B / LMDB Data Store"
description: "A high-performance distributed state store for Stroom using LMDB."
date: 2026-05-13
weight: 30
tags:
  - plan-b
  - reference-data
  - state
---


Plan B is a high-performance, distributed state store in Stroom that uses LMDB (Lightning Memory-Mapped Database) as its underlying storage engine.
It is designed to replace or supplement existing reference data and statistics features where high throughput and low-latency lookups are required.


## When to Use Plan B


### Use Plan B When


*   **High Performance Lookups**:
    You need to perform millions of ultra-fast lookups during event enrichment (e.g., in XSLT or StroomQL).
*   **Distributed State**:
    You need to maintain state that is updated by many processing nodes but needs to be available for querying globally.
*   **Large Reference Sets**:
    You have reference data sets that are too large to hold efficiently in heap-based caches.
*   **Complex State Types**:
    You need specific state behaviors like temporal state, ranged state, or session tracking.


### Use Something Else When


*   **Lazy Loading**:
    If you only need small fragments of a huge dataset occasionally and don't want to store it all in advance, traditional **Reference Data** (which loads lazily) might be better.
*   **Ad-hoc Queries**:
    For simple, non-recurring queries on indexed data, a standard **Lucene Index** and **Dashboard** are more appropriate.
*   **Relational Data**:
    If you need complex relational joins or ACID transactions across multiple tables, a traditional RDBMS is still the correct choice.


## Architecture


Plan B uses a "Local Write, Central Merge, Snapshot Read" architecture:


1.  **Writing**:
    Individual processing nodes write data to local LMDB instances (`writer` directory) via the `PlanBFilter` in a pipeline.
1.  **Uploading**:
    These local instances are zipped and uploaded to one or more configured **Storage Nodes**.
1.  **Merging**:
    Storage nodes run a background job (`Plan B Merge Processor`) to merge the uploaded fragments into master **Shards**.
1.  **Querying/Snapshots**:
    Non-storage nodes fetch **Snapshots** of these shards from storage nodes to perform local, high-speed lookups.


## Data Ingestion


To load data into Plan B, you create a pipeline that uses the **PlanBFilter** as its final destination element.
This filter consumes XML in the `reference-data:2` namespace.


### Example Setup: User Location Tracking


In this scenario, we want to track which building a user is in based on their badge scans.
We need a **Temporal State** store so we can find where a user was at any specific point in time.


#### 1. Plan B Document Configuration


Create a new **Plan B** document in the Explorer called `user_locations` with these settings:


*   **State Type**: `Temporal State` (Allows us to store a history of locations per user).
*   **Max Store Size**: `2 GiB` (Sufficient for several million history records).
*   **Retention**: `Enabled`, `90 Days` (We only care about the last 3 months of movement).


#### 2. Pipeline XML Output


Your pipeline's XSLT must produce XML following this structure:


```xml
<referenceData xmlns="reference-data:2" version="2.0">
    <reference>
        <!-- The map name must match the Plan B Doc name -->
        <map>user_locations</map>
        <!-- The unique identifier (e.g. Username) -->
        <key>jdoe</key>
        <!-- The value to store (e.g. Building Name) -->
        <value>North Wing</value>
        <!-- Required for Temporal State: The time this state became valid -->
        <effectiveTime>2026-05-13T10:30:00.000Z</effectiveTime>
    </reference>
</referenceData>
```


### Data Structure Explanation


*   **`<map>`**:
    Links the data to a specific Plan B document.
    This allows a single ingestion pipeline to update multiple state maps simultaneously.
*   **`<key>`**:
    The unique identifier for the entity.
    In a `Temporal State` store, multiple values can exist for the same key, distinguished by their `effectiveTime`.
*   **`<value>`**:
    The data associated with the key.
    This can be a simple string or a nested XML fragment.
*   **`<effectiveTime>`**:
    The "Start" time for this specific state.
    When performing a lookup, Stroom will return the value whose `effectiveTime` is closest to, but not after, the requested lookup time.


## Looking Up Data


Data stored in Plan B can be retrieved during pipeline processing or within dashboard queries.


### XSLT Lookups (Enrichment)


In a processing pipeline, you use the `stroom:lookup()` extension function within your XSLT to enrich event data.


#### Implementation Example


This example shows how to add a `Location` field to an event by looking up the user's location in the `user_locations` Plan B store.


```xslt
<xsl:template match="Event">
  <xsl:copy>
    <xsl:variable name="userId" select="EventSource/User/Id"/>
    <xsl:variable name="eventTime" select="EventTime/TimeCreated"/>

    <!-- Perform the lookup -->
    <xsl:variable name="location" select="stroom:lookup('user_locations', $userId, $eventTime)"/>

    <EventDetail>
      <data name="User" value="{$userId}"/>
      
      <!-- Only add the field if a location was found -->
      <xsl:if test="$location">
        <data name="LastKnownLocation" value="{$location}"/>
      </xsl:if>
      
      <xsl:apply-templates select="EventDetail/*"/>
    </EventDetail>
  </xsl:copy>
</xsl:template>
```


#### Advanced: Handling XML Fragment Values
If your Plan B store contains nested XML in the `<value>` field (e.g., `<value><building>North</building><floor>3</floor></value>`), you can access those elements directly:


```xslt
<xsl:variable name="locData" select="stroom:lookup('user_locations', $userId, $eventTime)"/>
<data name="Floor" value="{$locData/floor}"/>
```


### Dashboard and StroomQL Lookups (Analysis)


Dashboards can perform lookups on-the-fly when displaying data, which is useful for showing the "current" state of an entity.


#### In a Dashboard Table


You can use the `getState()` function in the **Expression** field of a table column.


1.  Add a new column to your table.
2.  Set the **Expression** to:
    `getState('user_locations', ${User})`
3.  This will display the most recent location for the user identified in the `User` column.


#### In a StroomQL Query


You can use lookups in the `where` or `select` clauses of a StroomQL query to filter or label results based on state.


```sql
// Show all events that occurred while users were in the 'North Wing'
from "My_Event_Index"
where getState('user_locations', User, EventTime) = "North Wing"
select EventTime, User, Action
```


{{% note %}}
Performing lookups in a dashboard or StroomQL query is convenient but can be slower than using an enriched index if you are processing millions of rows, as the lookup is performed for every row displayed or filtered.
{{% /note %}}


## Configuration Parameters


### Global Properties


These properties are configured in `stroom.conf`.
They control the underlying infrastructure and resource management for Plan B across the cluster.


*   **`stroom.planb.path`**:
    The root directory on the local filesystem for all Plan B data (writer fragments, shards, and snapshots).
    *   **Considerations**:
        LMDB performs best on fast storage (SSDs/NVMe).
        Ensure the disk has sufficient space for the master shards on storage nodes and snapshots on client nodes.
    *   **Starting Value**:
        `${stroom.home}/planb`.
*   **`stroom.planb.nodeList`**:
    A comma-separated list of node names designated as central **Storage Nodes**.
    These nodes are responsible for merging data fragments and served snapshots.
    *   **Considerations**:
        In a cluster, select 2 or 3 stable nodes with high I/O throughput.
        This provides high availability for the state data.
    *   **Starting Value**:
        Leave empty for a single-node setup.
        In a cluster, specify the names of your dedicated storage nodes.
*   **`stroom.planb.minTimeToKeepSnapshots`**:
    The minimum duration a node will keep a cached snapshot before checking the storage nodes for a newer version.
    *   **Considerations**:
        Balance data consistency against network and CPU overhead.
        If your state data updates frequently and lookups must be near-real-time, lower this value.
        For static reference data, a higher value is better.
    *   **Starting Value**:
        `10m` (10 minutes).
*   **`stroom.planb.minTimeToKeepSnapshotEnv`**:
    How long an LMDB environment (the memory-mapped file handle) for a snapshot remains open after its last use.
    *   **Considerations**:
        Opening and closing LMDB environments is relatively expensive.
        Keeping them open uses system file handles and address space.
        This should typically be at least twice the value of `minTimeToKeepSnapshots`.
    *   **Starting Value**:
        `20m` (20 minutes).
*   **`stroom.planb.snapshotRetryFetchInterval`**:
    How long to wait before retrying a failed attempt to fetch a snapshot from a storage node.
    *   **Considerations**:
        If the network is unstable or a storage node is temporarily busy, this prevents constant retries while ensuring eventual recovery.
    *   **Starting Value**:
        `1m` (1 minute).
*   **`stroom.planb.stateDocCache`**:
    Configuration for the internal cache that stores Plan B document definitions.
    *   **Considerations**:
        Usually only needs adjustment in very large environments with thousands of Plan B documents.
    *   **Starting Value**:
        `maximumSize: 1000`, `expireAfterWrite: 10m`.


### Plan B Document Settings


These settings are configured per Plan B document in the Stroom UI.


#### State Type


*   **State**:
    The simplest form, a direct Key-Value mapping.
    Ideal for static or slowly changing reference data.
*   **Temporal State**:
    Key-Value mapping with an associated `effectiveTime`.
    This allows for historical lookups, returning the value that was valid at a specific point in time.
*   **Range State**:
    Maps numeric ranges (e.g., Long or Integer ranges) to a value.
    Frequently used for CIDR blocks or numeric ID ranges.
*   **Temporal Range State**:
    Combines numeric ranges with temporal sensitivity.
    Useful for ranges that change over time (e.g., dynamic IP assignments).
*   **Session**:
    Specialized for tracking the lifecycle of an entity.
    It stores start and end times, allowing for duration analysis and state tracking (e.g., user sessions or process execution).
*   **Histogram**:
    Stores aggregated distribution data for numeric values.
    It counts occurrences within specified buckets, facilitating statistical analysis of data spread.
*   **Metric**:
    Designed for high-speed statistical counters and gauges.
    It maintains running aggregates such as `min`, `max`, `sum`, `count`, and `average` over time for a given key.
*   **Trace**:
    An OpenTelemetry-compatible store for distributed tracing data.
    It stores Spans and Trace information, enabling the analysis of complex, cross-system call chains.


#### Max Store Size


The maximum size the LMDB environment can grow to for this specific store.


*   **Considerations**:
    LMDB uses memory-mapped files.
    The `Max Store Size` sets the virtual address space limit.
    On many operating systems, this doesn't immediately consume physical disk space, but the filesystem must be able to accommodate the file if it fills up.
*   **Choosing a Value**:
    *   **Development/Small Maps**: 1 GiB - 5 GiB.
    *   **Production State**: 10 GiB (Default) - 100 GiB.
    *   **High-Volume Metrics**: 500 GiB+.
*   **Environment Example**:
    For a `user_locations` map in a large enterprise, where you expect 10 million entries, a value of 10 GiB is a safe starting point.


#### Overwrite


Determines whether incoming data should replace existing records with the same key (and the same `effectiveTime` for temporal types).


*   **Considerations**:
    Set this to `True` if you only care about the *latest* update for a specific point in time (e.g., correcting an erroneous badge scan).
    Set this to `False` if you want to prevent existing data from being accidentally changed by re-processing old streams.
*   **Application Example**:
    *   **Inventory Tracking**: `Overwrite = True` (The latest scan is the most accurate).
    *   **Audit Evidence**: `Overwrite = False` (Preserve the original entry recorded at that time).


#### Retention


Controls the automatic deletion of old data to manage disk space.


*   **Considerations**:
    Retention is evaluated based on the time associated with the record (e.g., the `effectiveTime`).
    Ensure your retention period is longer than any lookups you intend to perform.
*   **Choosing a Value**:
    *   **Session Tracking**: 30 to 90 Days.
    *   **System Status**: 7 Days.
    *   **Long-term Asset Mapping**: Disabled (Keep indefinitely).


#### Snapshot Settings


These toggle whether different types of retrieval operations should use local **Snapshots** (high performance, slightly delayed) or query the **Storage Nodes** directly (near real-time, higher network load).


*   **Use Snapshots for Lookup**:
    Enables snapshots for `stroom:lookup()` in pipelines.
    Recommended for high-throughput enrichment.
*   **Use Snapshots for Get**:
    Enables snapshots for `getState()` in dashboards.
*   **Use Snapshots for Query**:
    Enables snapshots for broad StroomQL `from "PlanB_Doc"` queries.


*   **Decision Matrix**:
    *   **Maximum Speed**: Enable all snapshots.
        This is best for SOC environments where thousands of events per second need enrichment.
    *   **Maximum Freshness**: Disable snapshots.
        Best for real-time monitoring dashboards where you need to see badge scans the moment they are merged.


### Modifying Settings on Existing Stores


{{% warning %}}
Changing settings on a Plan B store that already contains data can have significant consequences.
{{% /warning %}}


*   **Safe to Change**:
    *   **Max Store Size**:
        Increasing this value is safe and takes effect the next time the store is opened (e.g., during the next merge or lookup).
    *   **Retention**:
        Changing retention periods will affect the next run of the `Plan B Maintenance Processor`.
        Reducing the period may result in immediate deletion of older records.
    *   **Overwrite**:
        Can be toggled at any time; it will only affect future data writes.
    *   **Snapshot Settings**:
        Can be toggled at any time to balance performance and data freshness.
*   **Requires Reset**:
    *   **State Type**:
        You cannot change the `State Type` of an existing store.
        If you need to change the type (e.g., from `State` to `Temporal State`), you must delete the Plan B document and recreate it, which will clear all existing data.
    *   **Schema Configuration**:
        For complex types like `Metric` or `Histogram`, changing the underlying key or value schemas will cause a "Schema version mismatch" error when Stroom attempts to open the existing LMDB file.
        In these cases, you must reset the store contents.


## Debugging Issues


### Check Directory Structure


Navigate to the Plan B path (e.g., `/stroom/planb`) to see where data is stuck:


*   **`writer/`**:
    Data currently being written by a pipeline.
*   **`receive/`**:
    Data uploaded to a storage node but not yet staged.
*   **`staging/`**:
    Data waiting for the `Plan B Merge Processor` job.
*   **`shards/`**:
    The final merged master data.
*   **`snapshots/`**:
    Local copies of shards on non-storage nodes.


### Check Background Jobs


Ensure the following jobs are enabled and running:


*   **Plan B Merge Processor**: Vital for moving data from staging to shards.
*   **Plan B Maintenance Processor**: Handles condensation, retention, and deletion of orphaned stores.
*   **Plan B Shard Cleanup**: Closes idle LMDB environments and cleans up old snapshots.


### Log Analysis


Stroom logs provide valuable information when troubleshooting Plan B operations.


#### Finding the Logs


Logs are typically found in the `logs/` directory of your Stroom installation.
Common file paths include:


*   **`logs/app/app.log`**:
    The primary application log containing information about merging, maintenance, and lookup errors.
*   **Containerized Environments**:
    If running in Docker, logs are often streamed to `stdout`/`stderr` and can be viewed using `docker logs <container_name>`.
*   **Linux Service**:
    If installed as a systemd service, logs may also be available via `journalctl -u stroom`.


#### What to Look For


Search for "Plan B" or "LMDB" in the logs.
Common issues include:


*   **Disk Full**:
    LMDB cannot expand the store if the disk is full.
*   **Network Failures**:
    Processing nodes cannot upload to storage nodes.
*   **Permission Denied**:
    Stroom user does not have write access to the Plan B path.
*   **Schema Version Mismatch**:
    Occurs if you change the schema of a Metric or Histogram store without resetting it.


## Resetting Contents


To completely reset the contents of a Plan B store:


1.  **Delete via UI**:
    Delete the Plan B document in the Explorer.
    The `Plan B Maintenance Processor` will eventually clean up the associated LMDB files on the storage nodes.
1.  **Manual Reset (Advanced)**:
    1.  Stop the `Plan B Merge Processor` job.
    1.  Delete the specific subdirectory for the store's UUID in the `shards/` directory on all storage nodes.
    1.  Delete the associated subdirectories in `snapshots/` on all client nodes.
    1.  Clear out `staging/` and `receive/` if they contain pending updates for that store.
    1.  Restart the jobs.


{{% note %}}
Manual reset should be used with caution and only when the UI deletion is not sufficient or possible.
{{% /note %}}


---


| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
