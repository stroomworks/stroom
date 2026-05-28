---
title: "Data Splitter (DSParser)"
linkTitle: "Data Splitter"
description: "How to use the Data Splitter (DSParser) pipeline component to parse structured text data."
date: 2026-05-14
weight: 5
tags:
  - data-splitter
  - parser
  - csv
  - log
---


The **Data Splitter (DSParser)** is a powerful pipeline component in Stroom used to transform structured or semi-structured plain text data into XML.
It uses a Domain Specific Language (DSL) defined in XML to describe how to split, match, and extract data from the input stream.


## Overview


The `DSParser` component is typically used as the first stage in a pipeline (often preceded by a `Reader`).
It takes a **Text Converter** as its configuration, which contains the Data Splitter XML.


Key elements of the Data Splitter DSL:


`<split>`
: Splits the input based on a delimiter (e.g., newline, comma).


`<regex>`
: Uses regular expressions to extract named groups.


`<group>`
: Groups related data elements.


`<data>`
: Outputs a data element into the resulting XML.


`<var>`
: Stores a value in a variable for later use.


## Common Format Examples


### 1. CSV (Comma Separated Values)


For a standard CSV file without a header, you can use a nested split approach.


**Sample Input:**
```text
2020-06-17T08:00:00.000Z,jim,warehouse,logon
2020-06-17T08:10:00.000Z,bob,office,logon
```


**Data Splitter Configuration:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<dataSplitter xmlns="data-splitter:3" version="3.0">
  <!-- Split the input into lines -->
  <split delimiter="\n">
    <group value="$1">
      <!-- Split each line into fields by comma -->
      <split delimiter=",">
        <data value="$1" />
      </split>
    </group>
  </split>
</dataSplitter>
```


**Sample Output:**
```xml
<records xmlns="records:2">
  <record>
    <data value="2020-06-17T08:00:00.000Z"/>
    <data value="jim"/>
    <data value="warehouse"/>
    <data value="logon"/>
  </record>
  <record>
    <data value="2020-06-17T08:10:00.000Z"/>
    <data value="bob"/>
    <data value="office"/>
    <data value="logon"/>
  </record>
</records>
```


### 2. CSV with Heading


If the CSV file includes a heading line, you can capture the headings into a variable list and then use those variables as the names for the subsequent data elements.


**Sample Input:**
```text
dt,who,where,what
2020-06-17T08:00:00.000Z,jim,warehouse,logon
```


**Data Splitter Configuration:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<dataSplitter xmlns="data-splitter:3" version="3.0">
  <!-- Match heading line (note that maxMatch="1" means that only the first line will be matched by this splitter) -->
  <split delimiter="\n" maxMatch="1">
    <group>
      <split delimiter=",">
        <var id="heading" />
      </split>
    </group>
  </split>

  <!-- Match each subsequent record -->
  <split delimiter="\n">
    <group value="$1">
      <split delimiter=",">
        <!-- Output the stored heading for each iteration and the value from group 1 -->
        <data name="$heading$1" value="$1" />
      </split>
    </group>
  </split>
</dataSplitter>
```


**Sample Output:**
```xml
<records xmlns="records:2">
  <record>
    <data name="dt" value="2020-06-17T08:00:00.000Z"/>
    <data name="who" value="jim"/>
    <data name="where" value="warehouse"/>
    <data name="what" value="logon"/>
  </record>
</records>
```


### 3. Apache Webserver Logs (Combined Format)


Apache logs in the "combined" format are best parsed using a regular expression.


**Sample Log:**
`127.0.0.1 - frank [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326`


**Data Splitter Configuration:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<dataSplitter xmlns="data-splitter:3" version="3.0">
  <split delimiter="\n">
    <group value="$1">
      <regex pattern="^([^ ]+) ([^ ]+) ([^ ]+) \[([^\]]+)\] &#34;([^&#34;]+)&#34; ([^ ]+) ([^ ]+)">
        <data name="RemoteHost" value="$1" />
        <data name="Ident" value="$2" />
        <data name="User" value="$3" />
        <data name="Time" value="$4" />
        <data name="Request" value="$5" />
        <data name="Status" value="$6" />
        <data name="Size" value="$7" />
      </regex>
    </group>
  </split>
</dataSplitter>
```


**Sample Output:**
```xml
<records xmlns="records:2">
  <record>
    <data name="RemoteHost" value="127.0.0.1"/>
    <data name="Ident" value="-"/>
    <data name="User" value="frank"/>
    <data name="Time" value="10/Oct/2000:13:55:36 -0700"/>
    <data name="Request" value="GET /apache_pb.gif HTTP/1.0"/>
    <data name="Status" value="200"/>
    <data name="Size" value="2326"/>
  </record>
</records>
```


### 4. Linux Login Logs (sshd)


System logs like `/var/log/auth.log` contain repetitive patterns for login events.


**Sample Input:**
`May 14 10:11:12 myhost sshd[1234]: Accepted password for jsmith from 192.168.1.100 port 54321 ssh2`


**Data Splitter Configuration:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<dataSplitter xmlns="data-splitter:3" version="3.0">
  <split delimiter="\n">
    <group value="$1">
      <regex pattern="^([A-Z][a-z]{2}\s+\d+\s+\d{2}:\d{2}:\d{2})\s+([\w\.-]+)\s+sshd\[(\d+)\]:\s+(Accepted|Failed)\s+(\w+)\s+for\s+(\w+)\s+from\s+([\d\.]+)\s+port\s+(\d+)">
        <data name="Timestamp" value="$1" />
        <data name="Hostname" value="$2" />
        <data name="PID" value="$3" />
        <data name="Result" value="$4" />
        <data name="AuthMethod" value="$5" />
        <data name="User" value="$6" />
        <data name="Source_IP" value="$7" />
        <data name="Source_Port" value="$8" />
      </regex>
    </group>
  </split>
</dataSplitter>
```


**Sample Output:**
```xml
<records xmlns="records:2">
  <record>
    <data name="Timestamp" value="May 14 10:11:12"/>
    <data name="Hostname" value="myhost"/>
    <data name="PID" value="1234"/>
    <data name="Result" value="Accepted"/>
    <data name="AuthMethod" value="password"/>
    <data name="User" value="jsmith"/>
    <data name="Source_IP" value="192.168.1.100"/>
    <data name="Source_Port" value="54321"/>
  </record>
</records>
```


### 5. Windows Event Logs (Structured Text)


When Windows Event Logs are exported as structured text (e.g., via a collection agent), they often follow a Key: Value pattern.


**Sample Input:**
```text
TimeGenerated: 20260514101112.000000-000
EventID: 4624
SourceName: Microsoft-Windows-Security-Auditing
ComputerName: WIN-SERVER-01
Message: An account was successfully logged on.
```


**Data Splitter Configuration:**
Using a multi-line regex with `dotall="true"` allows matching across the entire event record.


```xml
<?xml version="1.0" encoding="UTF-8"?>
<dataSplitter xmlns="data-splitter:3" version="3.0">
  <!-- Split on double newline assuming it separates events -->
  <split delimiter="\n\n">
    <group value="$1">
      <regex dotall="true" pattern="TimeGenerated:\s+(.*)\nEventID:\s+(\d+)\nSourceName:\s+(.*)\nComputerName:\s+(.*)\nMessage:\s+(.*)">
        <data name="TimeGenerated" value="$1" />
        <data name="EventID" value="$2" />
        <data name="Source" value="$3" />
        <data name="Computer" value="$4" />
        <data name="Message" value="$5" />
      </regex>
    </group>
  </split>
</dataSplitter>
```


**Sample Output:**
```xml
<records xmlns="records:2">
  <record>
    <data name="TimeGenerated" value="20260514101112.000000-000"/>
    <data name="EventID" value="4624"/>
    <data name="Source" value="Microsoft-Windows-Security-Auditing"/>
    <data name="Computer" value="WIN-SERVER-01"/>
    <data name="Message" value="An account was successfully logged on."/>
  </record>
</records>
```


## The Context Part of a Stream


In Stroom, a stream can have a **Context** part (often delivered as a `.ctx` file within a zip upload).
This part provides metadata that is specific to that stream or even to individual records within the stream.


Unlike general **Reference Data**, which is effective-time sensitive and shared across many feeds, **Context Data** is intrinsically linked to the main data stream it accompanies.


### How Context is Used


1.  **Per-Record Metadata**:
    If the context data is structured to match the records in the main data (e.g., both have 100 records), Stroom can automatically index them such that record 5 of the main data is enriched with metadata from record 5 of the context data.
1.  **Lookup in XSLT**:
    You can use the `stroom:lookup` function with a stream type of `'CONTEXT'` to retrieve values from the context part during pipeline processing.


```xml
<!-- Example of looking up a machine name from the context part -->
<Site>
  <xsl:value-of select="stroom:lookup('CONTEXT', 'Machine', $time)" />
</Site>
```


### When to Use Context


Use the context part when you have metadata that:
- Changes as frequently as the data itself.
- Is only relevant to the specific data it is delivered with.
- Needs to be linked precisely to individual records or the stream as a whole without the overhead of maintaining a separate reference feed.


## Best Practices


1.  **Use `dotall="true"` sparingly**:
    It can make regex performance slower on very large inputs.
1.  **Escape special characters**:
    Remember that XML special characters like `"` must be escaped as `&#34;` or `&quot;` within the `pattern` attribute if you are not using a CDATA block.
1.  **Validate RegEx**:
    Test your regular expressions against sample data before incorporating them into a Data Splitter configuration.
1.  **Stepping**:
    Use the Stroom Pipeline Stepping feature to debug your Data Splitter and see exactly how it is parsing your data at each step.
