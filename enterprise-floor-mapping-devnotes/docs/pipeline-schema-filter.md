# Schema Filter Configuration

The `SchemaFilter` pipeline element is used to perform inline XML schema validation of XML data as it flows through a pipeline. This ensures that the data conforms to the expected structure and data types defined in one or more XML Schemas.

## Parameters

| Parameter | Description | Default Value |
| --- | --- | --- |
| `schemaGroup` | Limits the schemas that can be used to validate data to those with a matching schema group name. | (None) |
| `systemId` | Limits the schemas that can be used to validate data to those with a matching system id. | (None) |
| `namespaceURI` | Limits the schemas that can be used to validate data to those with a matching namespace URI. | (None) |
| `schemaLanguage` | The schema language that the schema is written in. Usually `http://www.w3.org/2001/XMLSchema`. | `http://www.w3.org/2001/XMLSchema` |
| `schemaValidation` | A boolean flag to enable or disable schema validation. | `true` |

## How to Choose Parameters

### 1. `schemaGroup` (Recommended)

The `schemaGroup` is the most common way to filter which schemas Stroom should consider for validation. 

*   **When to use:** Use this when you have multiple schemas in Stroom and you want to ensure the pipeline only validates against a specific set (e.g., all versions of the "EVENTS" schema).
*   **How to set:** Set this to the value matching the **Schema Group** field defined in your XML Schema document(s). For example, if your schemas for event data are grouped under "EVENTS", set this parameter to `EVENTS`.

### 2. `systemId`

The `systemId` is a unique identifier for a specific schema document in Stroom. It typically corresponds to the file name or a URI used in the `xsi:schemaLocation` attribute of your XML.

*   **When to use:** Use this if you want to force validation against one specific schema version or if you have specific system IDs defined in your XML's `schemaLocation` that you want to match explicitly.
*   **How to set:** Set this to match the **System Id** field in the XML Schema document.

### 3. `namespaceURI`

The `namespaceURI` is the target namespace of the XML schema.

*   **When to use:** Use this if you want to limit validation to a specific XML namespace.
*   **How to set:** Set this to the namespace URI defined in your XML and schema (e.g., `records:2`).

### 4. `schemaLanguage`

*   **When to use:** Almost always left as the default (`http://www.w3.org/2001/XMLSchema`). Only change this if you are using a different schema language that Stroom supports (though W3C XML Schema is the standard).

### 5. `schemaValidation`

*   **When to use:** Set to `true` (default) to perform validation. Set to `false` if you want to temporarily disable validation without removing the element from the pipeline, perhaps for troubleshooting performance issues.

## Configuration Strategy

For most pipelines, you should:
1.  Add the `SchemaFilter` after your `XSLTFilter` or `Parser`.
2.  Set the `schemaGroup` to match the logical group of schemas you are using (e.g., `RECORDS`).
3.  Ensure your XML data includes the correct `xmlns` and `xsi:schemaLocation` attributes in the root element to help the `SchemaFilter` locate the correct schema within the specified group.
