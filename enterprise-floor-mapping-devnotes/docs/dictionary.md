---
title: "Dictionary"
linkTitle: "Dictionary"
description: "Understanding Dictionaries in Stroom: managing word lists for filtering and data enrichment."
date: 2026-05-14
weight: 6
tags:
  - dictionary
  - filtering
  - xslt
---


A **Dictionary** in Stroom is essentially a list of 'words', where each 'word' is a single line of text.
Dictionaries are primarily used to define sets of values that can be reused across different parts of Stroom, such as filtering in search expressions or data enrichment in XSLT.


## How Data Goes In


Data can be added to a Dictionary in several ways:


### 1. Manual Entry via UI
Users can directly type or paste a list of words into the **Data** tab of a Dictionary document.
Each word must be on a new line.


### 2. Import/Export
Dictionaries are standard Stroom documents and can be imported or exported as part of a content pack (ZIP file).


### 3. DictionaryAppender
The `DictionaryAppender` pipeline element can be used to write the output of a pipeline into a Dictionary.
Note that the `DictionaryAppender` **overwrites** all previous data in the target dictionary.


### 4. Inheritance (Imports)
Dictionaries support inheritance.
One dictionary can **import** the contents of other dictionaries.
When a dictionary is used, Stroom combines its own data with the data from all its imported dictionaries (recursively).


## How Data Comes Out


Dictionaries are used in several contexts:


### 1. Filtering with `IN DICTIONARY`
In Stroom QL or Dashboard expressions, you can use the `in dictionary` condition to check if a field value exists within a specific dictionary.


```stroomql
where IPAddress in dictionary "Internal Networks"
```


### 2. Receive Data Rules
Dictionaries can be used in Receive Data Rules to filter data as it is being received by Stroom (e.g., dropping data from certain source IPs).


### 3. XSLT Functions
The `stroom:dictionary` XSLT function allows you to retrieve the combined content of a dictionary as a single newline-separated string.


```xml
<xsl:variable name="wordList" select="stroom:dictionary('Internal Networks')"/>
```


## When to Use Dictionaries


Dictionaries are best used when:
- You have a set of values (e.g., IP addresses, usernames, status codes) that change frequently and are used in multiple places.
- You want to simplify search expressions by grouping related values.
- You need to share common lists of values across multiple feeds or dashboards.
- You want to define a hierarchy of values (e.g., a "All Servers" dictionary that imports "Web Servers" and "DB Servers").


## Configuration Options


A Dictionary document has the following main configuration areas:


### Data Tab
This is where the actual list of words is stored.
Each line is treated as a separate entry.


### Imports Tab
A list of other Dictionary documents that this dictionary should inherit data from.
The order of imports is typically not significant as the result is a combined set of unique lines.


## Considerations


- **Performance**: Very large dictionaries (hundreds of thousands of lines) can impact search performance when used with `in dictionary`. Consider using Reference Data for extremely large lookup sets.
- **Overwriting**: Using `DictionaryAppender` will completely replace the content of the dictionary. If you need to append data, you may need a more complex pipeline or manual management.
- **Recursion**: Stroom handles recursive imports, but it is best practice to keep the inheritance hierarchy simple to maintain readability.
- **Permissions**: To use a dictionary in a search or pipeline, the user running the process must have at least **Read** permission on the Dictionary document.


## Limitations


- **Flat Structure**: A dictionary is a simple list of strings. It does not support key-value pairs or structured data. For those use cases, use **Reference Data**.
- **Case Sensitivity**: The `in dictionary` condition's case sensitivity depends on the underlying index or field configuration, but generally, dictionaries are treated as case-sensitive lists.
- **No Metadata**: You cannot associate metadata with individual words in a dictionary.
