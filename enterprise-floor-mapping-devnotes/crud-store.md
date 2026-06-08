# CRUD store for Enterprise Mapping

There will be a CRUD interface, implementing HasCrud. 
This interface will allow the Enterprise Mapping document to update data.

The data will be stored in the same format as an LMDB Temporal Store:

 - Map: String - identifies where the data will be stored - the name of the relevant Doc object which implements the store.  
 - Key: String
 - EffectiveTime: Instant
 - Value: String (might be JSON or XML)

Initially there will be two implementations of this interface:

1. LMDB Temporal Store, where the overwrite property is set to true
2. MySQL, storing the data in a table named `updatable_temporal_store`.

## Implementation Plan

### 1. API and Data Model
*   **Module**: `stroom-util-shared`
*   **Classes**:
    *   `TemporalEntry`: A POJO representing a single entry with fields: `map`, `key`, `Instant effectiveTime`, and `value`.
    *   `TemporalEntryId`: A POJO representing the unique identifier for an entry: `map`, `key`, and `Instant effectiveTime`.
*   **Interface**: `UpdatableTemporalStore` extending `stroom.util.shared.HasCrud<TemporalEntry, TemporalEntryId>`.
    *   Includes a synchronous `find(ExpressionCriteria criteria)` method for UI and internal service use.
    *   Implements `SearchProvider` to support Dashboard Query UI.
    *   Add a `void reset(String mapName)` to reset the content of an updatable temporal store.

### 2. Plan B (LMDB) Implementation

Note: This module is not to be implemented at this stage. The specification exists only to ensure that 
it is possible to implement this module at a future stage.

*   **Module**: `stroom-planb-impl`
*   **Class**: `UpdatablePlanBTemporalStore`
*   **Key Tasks**:
    *   **Write Operations**: Implement `create` and `update` by resolving the `PlanBDoc` and using `ShardWriter`.
    *   **Validation**: Verify the `PlanBDoc` has `overwrite` set to `true`. If not, throw a descriptive exception suggesting corrective action.
    *   **Permissions**: Verify the user has the required permissions to mutate the `PlanBDoc`.
    *   **Read Operation**: Implement `fetch` using `ShardManager` to acquire the relevant `Db` instance.
    *   **Delete Operation**: Implement `delete` using `dbi.delete` in the underlying store.
    *   **Permissions**: Permissions must be checked before any operation
    *   **Auditing**: Messages must be written to the audit log recording data mutations.


### 3. MySQL Implementation

There will be two parts to the implementation:

1. A SqlStoreDoc, similar to the PlanBDoc, that provides a central configuration point for a map stored in the SQL database. 
    - Defines the map_name for this store - from the name of the SqlStoreDoc itself. This is consistent with other Stroom stores.
    - Defines the access permissions for this store
    - Provides a UI to reset the contents of the store, if the user has permission to do so
    - Provides a UI showing the number of elements within the store
    - UI code will be in the `stroom-core-client` module
 
2. The UpdatableSqlTemporalStore, which provides the CRUD operations to other parts of the system.

*   **Modules**: `stroom-sqlstore`, `stroom-sqlstore-api`, `stroom-sqlstore-impl`, 
     `stroom-sqlstore-impl-db`, `stroom-sqlstore-impl-db-jooq` as in the normal Stroom pattern.
*   **Class**: `UpdatableSqlTemporalStore`
*   **Table**: `updatable_temporal_store` (Single table for all SQL-based maps).
*   **Schema**:
    *   `map_name`: VARCHAR(255)
    *   `key_`: VARCHAR(255)
    *   `effective_time`: BIGINT
    *   `value_`: LONGTEXT
    *   Primary Key: `(map_name, key_, effective_time)`
*   **Key Tasks**:
    *   **CRUD Operations**: Use jOOQ for DML. Use `UPSERT` logic for `create` and `update`. Check parameter lengths and throw exceptions with helpful messages if lengths are exceeded or parameters are invalid.
    *   **Validation & Permissions**: Verify user authorization for the requested map.
    *   **Error Handling**: Throw descriptive exceptions for SQL failures with suggested fixes.
    *   **Permissions**: Permissions must be checked before any operation
    *   **Auditing**: Messages must be written to the audit log recording data mutations.
    *   **StroomQL to SQL**: There will be a module to convert StroomSQL in the ExpressionCriteria into 
     JOOQ SQL. There will be extensive unit tests to demonstrate that this operates as expected.
     There will be extensive JavaDoc explaining the translation.
    *   **Database initialisation**: There will be a Flyway script to create the database table.

 * **New Task**:
    *   **Time Sensitive Values**: When asked for a value for a given timestamp, the search operation must return the first value where the effectiveTime is the given time or before.   
    *   **SQLStore Filter**: We need a pipeline filter that can send data to the SQL Store defined by the <map> entry in the XML. If the map isn't known the filter should generate an error with a meaningful message that will help the user resolve the problem.
   
### 4. Factory/Provider
*   **Class**: `UpdatableTemporalStoreProvider`
*   **Responsibility**: 
    *   **Selection**: Look for a SqlStoreDoc with the correct map_name. 
    *   **Map Validation**: If the map name is unknown/unconfigured, throw a meaningful exception (e.g., `UnknownStoreException`) that explains the problem to the user.
    *   **Note**: This class focuses strictly on selection and validation; permissions and store-specific config checks are handled by the implementations.
