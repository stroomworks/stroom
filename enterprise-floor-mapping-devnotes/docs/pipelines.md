---
title: "How Pipelines Work"
linkTitle: "Pipelines"
weight: 5
description: >
  An overview of the Stroom pipeline model, elements, and data flow.
tags:
  - pipeline
---


In Stroom, a **Pipeline** is a configurable sequence of processing steps used to parse, transform, and store data.
Think of it as a "factory assembly line" where raw data enters at one end and refined, structured data comes out the other.


## Core Concepts


### Elements and Links


A pipeline is composed of **Elements**.
Each element performs a specific job—reading a file, parsing text, transforming XML using XSLT, or writing to a database.


*   **Elements**:
    The individual "machines" in the factory.
*   **Links**:
    The "conveyor belts" that connect one element to the next, defining the direction of data flow.


For more details on specific elements, see {{< relref "intro-pipeline-elements.md" >}}. <!-- TODO Error here -->


### The Streaming Model (SAX)


Unlike many systems that load an entire file into memory (DOM), Stroom uses a **SAX-style streaming model**.
Data flows through the pipeline as a series of "events" (e.g., *Start Element*, *Characters*, *End Element*).
This allows Stroom to process massive multi-gigabyte files with a very small and stable memory footprint.


## Anatomy of a Typical Pipeline


A common pipeline for ingesting data usually follows this sequence:


1. {{< pipe-elm "Reader" >}}:
    Reads the raw bytes from a stream.
1.  **Parser**:
    Converts the raw text into XML events.
    The most common parser is the {{< pipe-elm "DSParser" >}}, which uses a {{< stroom-doc "TextConverter" >}} to turn CSV or log files into XML.
1.  **Filter**:
    Acts on the XML events.
    The most powerful filter is the {{< pipe-elm "XSLTFilter" >}}, which uses XSLT to transform the structure of the data.
    This includes tasks like renaming fields, adding timestamps, or performing lookups. Filters reference a {{< stroom-doc "XSLT" >}} document.
1.  **Writer**:
    Formats the XML events back into a specific format (e.g., {{< pipe-elm "XMLWriter" >}} or {{< pipe-elm "JSONWriter" >}}.
1.  **Appender**:
    Saves the final output to a destination.
    This could be another stream ({{< pipe-elm "StreamAppender" >}}), a search index ({{<pipe-elm "IndexingFilter" >}}), or a file.


## Pipeline Inheritance


One of Stroom's most powerful features is **Inheritance**.
You can create a "Base" pipeline that contains common elements (like the Reader and Parser).
Then you can create multiple "Child" pipelines that inherit those elements but add their own specific XSLT transformations.


*   **Benefit**:
    If you need to change the parsing logic for a specific log format, you change it in the Parent pipeline once.
    All Child pipelines are updated automatically.


## How Data Flows


When a pipeline runs, data moves through three distinct phases:


| Phase | Description |
| :--- | :--- |
| **Input** | The pipeline fetches data from a **Feed**. |
| **Processing** | Elements parse and transform the data according to the pipeline's configuration. |
| **Output** | The resulting data is written to a new **Stream**, an **Index**, or an external system. |


## The Stepper: Seeing Inside the Pipe


Because pipelines can be complex, Stroom [Feeds]({{< relref "feed" >}} ) provide a tool called the **Stepper** {{< stroom-icon "step.svg" >}}.
It allows you to "step" through the data record by record.
You can see the input and output of every single element in the pipeline simultaneously.
This makes it easy to identify exactly where a transformation might be failing.

You can find the {{< stroom-icon "step.svg" >}} button in the lower right-hand corner of the Feed Data page.


## Summary


*   **Pipelines are modular**:
    You build them by linking elements together.
*   **They are event-driven**:
    They process data as a stream, not in bulk.
*   **They are reusable**:
    Use inheritance to share logic across many feeds.


---

| Navigation          |                           |                   |
|:--------------------|---------------------------|------------------:|
| {{< prev-page >}}   | [Up]({{< relref "./" >}}) | {{< next-page >}} |
