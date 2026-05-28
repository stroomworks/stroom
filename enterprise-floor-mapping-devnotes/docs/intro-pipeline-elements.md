---
title: "Pipeline Elements"
linkTitle: "Pipeline Elements"
weight: 10
description: >
  Detailed information about the categories and individual elements used in Stroom pipelines.
---


A Stroom pipeline is built by connecting various **Elements**.
Each element belongs to a specific category and has a defined role in the data processing flow.


## Element Categories


Pipeline elements are grouped into several logical categories based on their function:


### Source


The entry point of a pipeline.
It defines where the data comes from.


*   {{< pipe-elm "Source" >}}:
    The standard element that provides the input stream from the Stroom data store.


### Parsers


Parsers convert raw input data (bytes) into XML events (SAX events).


*   {{< pipe-elm "DSParser" >}}:
    The Data Splitter parser.
    It uses a {{< stroom-doc "TextConverter" >}} (defined in a separate document) to parse structured text like CSV, TSV, or fixed-width logs into XML.
*   {{< pipe-elm "XMLParser" >}}:
    Used when the input data is already XML.
    It validates and parses the input into SAX events.
*   {{< pipe-elm "JSONParser" >}}:
    Converts input JSON into XML events conforming to the standard XSLT-JSON XML schema.
*   {{< pipe-elm "XMLFragmentParser" >}}:
    Wraps non-well-formed XML fragments with a root element to make them processable.


### Filters


Filters act upon the stream of XML events.
They can modify, split, or supplement the data.


*   {{< pipe-elm "XSLTFilter" >}}:
    The workhorse of Stroom transformations.
    It applies an XSLT stylesheet to the XML event stream.
    It is used for renaming fields, restructuring data, and performing lookups.
*   {{< pipe-elm "SchemaFilter" >}}:
    Performs inline XML schema (XSD) validation of the XML event stream.
    It ensures that the XML produced by a parser or XSLT conforms to a defined schema.
    See [Schema Filter Configuration]({{< relref "pipeline-schema-filter" >}}) for details on how to configure it.
*   {{< pipe-elm "ReferenceDataFilter" >}}:
    A specialized filter used to load reference data into an internal map for high-speed lookups in other pipelines.
*   {{< pipe-elm "RecordCountFilter" >}}:
    Simply counts the number of records passing through and can provide statistics.
*   {{< pipe-elm "SplitFilter" >}}:
    Used to split a single large XML stream into multiple smaller streams based on specific criteria.
*   {{< pipe-elm "IdEnrichmentFilter" >}}:
    Adds unique IDs to records.
*   {{< pipe-elm "RecordOutputFilter" >}}:
    Used during stepping to capture and display the state of the data at a specific point in the pipeline.


### Writers


Writers take the stream of XML events and convert them back into a serialized format.


*   {{< pipe-elm "XMLWriter" >}}:
    Serializes XML events back into a well-formed XML string.
*   {{< pipe-elm "JSONWriter" >}}:
    Converts XML events (conforming to the XSLT-JSON schema) into a JSON string.
*   {{< pipe-elm "TextWriter" >}}:
    Converts XML events into plain text, often used after an XSLT that has flattened the data.


### Destinations (Appenders)


Destinations define where the serialized output should be saved.


*   {{< pipe-elm "StreamAppender" >}}:
    Writes the output back into the Stroom data store as a new stream.
    You can specify the Feed and Stream Type for the output.
*   {{< pipe-elm "FileAppender" >}}:
    Writes the output to a file on the local file system.
*   {{< pipe-elm "HDFSFileAppender" >}}:
    Writes the output to a Hadoop Distributed File System (HDFS).
*   {{< pipe-elm "HTTPAppender" >}}:
    Posts the output to a remote HTTP(S) endpoint.
*   {{< pipe-elm "RollingStreamAppender" >}}:
    Similar to StreamAppender but "rolls" the stream based on size or time thresholds.
*   {{< pipe-elm "IndexingFilter" >}}:
    Technically a filter, but often acts as a destination by sending data directly into a Lucene or Elasticsearch index.


## Schema Validation


Using the {{< pipe-elm "SchemaFilter" >}} or enabling validation on parsers allows Stroom to ensure the structural integrity of the XML data as it flows through the pipeline.
Schemas are typically provided by the `core-xml-schemas` and `event-logging-xml-schemas` content packs.


### Advantages


*   **Data Quality**:
    Guarantees that the data conforms to the expected structure before it is indexed or stored.
*   **Early Error Detection**:
    Identifies issues in XSLT or parser logic immediately as they occur in the pipeline.
*   **Documentation**:
    Schemas act as a formal definition of the data format.
*   **Standardization**:
    Encourages the use of standard formats like the Event Logging schema.


### Disadvantages


*   **Performance Overhead**:
    XML schema validation is a computationally expensive process and can significantly reduce pipeline throughput.
*   **Schema Maintenance**:
    Requires schemas to be kept up to date as data formats evolve.
*   **Rigidity**:
    If an unexpected but valid variation in data occurs, the pipeline may fail or drop records if the schema is too restrictive.


{{% note %}}
It is common practice to enable schema validation during pipeline development and testing, but disable it in high-volume production environments once the processing logic is proven, to maximize performance.
{{% /note %}}


## Common Element Properties


Most elements have properties that control their behavior.
Common ones include:


| Property | Description |
| :--- | :--- |
| **Feed** | (Appenders) The feed to which the output stream should be assigned. |
| **Stream Type** | (Appenders) The type of data (e.g., Raw Events, Events, Context). |
| **XSLT** | (XSLTFilter) The DocRef of the XSLT stylesheet to apply. |
| **Text Converter** | (DSParser) The DocRef of the Text Converter configuration to use. |
| **Encoding** | (Writers) The character encoding (e.g., UTF-8) to use for the output. |


## Role and Visibility


When viewing elements in the Pipeline UI, you may see different roles:


*   **Target**:
    The element receives data from a previous element.
*   **Mutator**:
    The element modifies the data as it passes through.
*   **Destination**:
    The element is an end-point for the data.


Elements also have different **Visibility**:


*   **Simple**:
    Visible in the standard pipeline editor.
*   **Stepping**:
    Visible and interactive during the debugging/stepping process.


---

| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
