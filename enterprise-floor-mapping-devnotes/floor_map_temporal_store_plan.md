# Plan: Storing and Retrieving Objects and Backgrounds via SQL Temporal Store

This document specifies the design, data formats, coordinate transformations, and implementation details for transitioning floor map backgrounds and items to a SQL Temporal Store and Document Asset Store.

---

## 1. System Architecture & Components

1. **FloorMapDoc Configuration**:
   * The `FloorMapDoc` stores a reference to a `SqlTemporalStoreDoc` via a `DocRef` field (e.g., `temporalStoreRef`).
   * The name of this referenced `SqlTemporalStoreDoc` acts as the map/datasource name for queries and REST operations.

2. **Asset Storage (Binaries)**:
   * Large image files (PNG, SVG, JPEG) are uploaded via the **Assets** tab of the `FloorMapDoc` and saved to the Document Asset Store.
   * Binaries are served by the Asset Store Servlet: `http://<host>/assets/<docUuid>/<assetPath>`

3. **Data Storage (Metadata)**:
   * Map backgrounds and other items are registered as entries in the SQL Temporal Store (`UPDATABLE_TEMPORAL_STORE` table) under the `map` field (matching the selected `SqlTemporalStoreDoc`'s name).
   * Changes in Edit Mode are accumulated locally on the client and synchronized to the database via the REST CRUD API (`SqlTemporalStoreResource`) when the user saves the `FloorMapDoc`.

---

## 2. Temporal Entry Data Format

Each entry in the SQL Temporal Store is represented by `TemporalEntry`:
* **`map`**: The name of the selected `SqlTemporalStoreDoc`.
* **`key`**: The unique ID of the item.
* **`effectiveTimeMs`**: The timestamp (Long) at which this version of the record becomes valid.
* **`value`**: A serialized JSON string with the following fields:

```json
{
  "type": "background",
  "name": "Ground Floor Map",
  "coords": [0.0, 0.0],
  "img": "/images/ground_floor.svg",
  "tm-world-to-map": [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
  "tm-map-to-screen": [1.2, 0.0, 0.0, 1.2, 100.0, 50.0]
}
```

### Fields Specification:
* **`type`** (String, Required): e.g., `"background"` (special type rendered as map background) or other custom types (e.g., `"gates"`, `"computers"`).
* **`name`** (String, Required): User-friendly display name of the item. Editable in the UI.
* **`maps`** (Array of Strings, Optional): List of background item IDs (keys) on which this item is visible. If the active background ID is not present in this list, the item is hidden. *Note: Items of `type: "background"` do not have a `maps` entry.*
* **`coords`** (Array of 2 Numbers, Optional): The 2D world coordinates `[worldX, worldY]`. Defaults to `[0.0, 0.0]` if not specified. Editable via a text box.
* **`img`** (String, Optional): Relative path of the asset within the `FloorMapDoc` assets store. If not present, the item is rendered on screen as a fallback shape (such as a colored square). Editable via a point-and-click tree selection UI.
* **`tm-world-to-map`** (JSON Array of 6 Numbers, Optional): 2D transformation matrix mapping world coordinates into map-space. Stored as a JSON array `[a, b, c, d, e, f]`. Defaults to the identity matrix `[1.0, 0.0, 0.0, 1.0, 0.0, 0.0]`. Dragging a non-background item updates this matrix.
* **`tm-map-to-screen`** (JSON Array of 6 Numbers, Optional): 2D transformation matrix mapping map-space into screen space. Only applicable for items with `type: "background"`. Stored as a JSON array `[a, b, c, d, e, f]`. Dragging/rescaling a background image updates this matrix.

---

## 3. Coordinate Transformations & Dragging Mathematics

### Coordinate Spaces:
* **World Coordinates**: `C_world = [worldX, worldY]` (defined in the `coords` JSON field).
* **Map Coordinates**: `C_map = [mapX, mapY]` (the coordinate space of the floor map image).
* **Screen Coordinates**: `C_screen = [screenX, screenY]` (the raw canvas pixels on the browser screen).
* **Zoom/Pan Matrix**: `T_zoom_pan` (controlled by UI scrolling and dragging on the canvas).
* **Map-to-Screen Matrix**: `M_map_to_screen` (from the active background's matrix array `[a, b, c, d, e, f]`).
* **World-to-Map Matrix**: `M_world_to_map` (from the item's matrix array `[a, b, c, d, e, f]`).

### Rendering Transformation:
1. For a **background** item (where World Coordinates are assumed to be `[0, 0]`):
   `C_screen = T_zoom_pan * M_map_to_screen * [0, 0]`
2. For a **non-background** item:
   First, transform to map space:
   `mapX = a * worldX + c * worldY + e`
   `mapY = b * worldX + d * worldY + f`
   *(where a, b, c, d, e, f are from the item's tm-world-to-map matrix array)*
   
   Second, transform to screen space:
   `C_screen = T_zoom_pan * M_map_to_screen * [mapX, mapY]`

### Edit Mode Drag-and-Drop Inverse Mathematics:
When dragging an item to a new mouse position `C_screen`:
1. Revert the zoom/pan transform to get target coordinate in screen space:
   `C_screen_unzoomed = Inverse(T_zoom_pan) * C_screen`
2. **If dragging a background item**:
   * Update the translation components `e` and `f` (at indices 4 and 5 in the matrix array) of the background's `tm-map-to-screen` matrix by the dragging offset in unzoomed screen space.
3. **If dragging a non-background item**:
   * Revert the active background's map-to-screen matrix to get target map coordinates:
     `[mapX, mapY] = Inverse(M_map_to_screen) * C_screen_unzoomed`
   * Update the translation components `e` and `f` (at indices 4 and 5) of the item's `tm-world-to-map` matrix while keeping its scale/rotation parameters (`a, b, c, d` at indices 0, 1, 2, 3) constant:
     `e = mapX - (a * worldX + c * worldY)`
     `f = mapY - (b * worldX + d * worldY)`
     *(If coords is omitted or not present, assume `worldX = 0, worldY = 0`, giving `e = mapX` and `f = mapY`)*

---

## 4. Query Mechanism

The `FloorMapDoc` features two separate Query tabs:

### A. Events Query (Dynamic Visualization)
* Displays real-time dynamic events (such as log data, user movements, and locations) queried from standard event sources in Stroom.
* This tab operates exactly like the current read-only Query page.

### B. Facts Query (Items & Background Playback)
* Displays metadata facts (background maps, gates, computers) queried from the SQL Temporal Store.
* The query is resolved against the SQL Temporal Store selected under the Settings tab.
* **Initial/Default Query Template**:
  ```text
  from "<StoreName>"
  select 
    Key, 
    EffectiveTime, 
    jq(Value, ".type") as type, 
    jq(Value, ".name") as name, 
    jq(Value, ".maps") as maps, 
    jq(Value, ".coords") as coords, 
    jq(Value, ".img") as img, 
    jq(Value, ".\"tm-world-to-map\"") as tm_world_to_map, 
    jq(Value, ".\"tm-map-to-screen\"") as tm_map_to_screen
  ```
  *(Where `<StoreName>` is the name of the `SqlTemporalStoreDoc` document).*
* StroomQL's temporal lookup automatically queries for entries valid at or before the selected playback timeline time.

---

## 5. UI Lifecycle & Saving

1. **Edit Mode UI**:
   * The FloorMap UI exposes an **Edit Mode** toggle.
   * Modifying items (dragging, updating fields, choosing images via the asset selection tree) keeps changes in GWT memory and marks the FloorMap document dirty.
2. **Saving**:
   * When saving the `FloorMapDoc`, the GWT client triggers a post-save callback.
   * The callback invokes `SqlTemporalStoreResource` REST endpoints (`create`, `update`, `entry/delete`) to sync the local memory entries back to the database in a transactional/batch fashion.

---

## 6. GWT User Interface Implementation Details

### A. Layout Structure
The main `FloorMapPresenter` document view contains the following tabs:
1. **Map**: The interactive map canvas.
2. **Events Query**: Dedicated StroomQL query tab for live event tracking.
3. **Facts Query**: Dedicated StroomQL query tab for store items and background map retrieval.
4. **Settings**: Selection of the `SqlTemporalStoreDoc` configuration reference.
5. **Assets**: Document asset uploads.
6. **Documentation**: Markdown page.

### B. Drag-and-Drop and Scaling Interactions in Edit Mode
* **Plotted Items (Gates/Computers)**:
  * When in Edit Mode, non-background items can be dragged directly on the map.
  * Dragging updates the item's local coordinates in memory (calculating the new `tm-world-to-map` offset components `e` and `f` as specified in Section 3) and redraws the canvas.
  * *Note: Edits are for an effective time. An item may move at any time.*
* **Backgrounds**:
  * Selecting a background image displays transformation handles on the canvas.
  * Dragging handles scales or rotates the background image, updating components `a, b, c, d` of its `tm-map-to-screen` matrix.
  * Dragging the background itself pans/translates the background, updating components `e` and `f`.

### C. GWT Client Memory State & Document Dirtying
* Changing any item's field or dragging it on screen updates a local GWT state (accumulated as added, modified, or deleted records relative to their `effectiveTimeMs` and `key`).
* Any local modification marks the presenter as dirty via GWT `ChangeEvent.fire()`, which lights up the main Stroom save button.
* When the user clicks **Save** in the main toolbar, the `FloorMapPresenter` runs its save flow:
  1. Writes the main `FloorMapDoc` document.
  2. Batch writes only the changed items (added/modified/deleted) in memory to the store using `/sqltemporalstore/entry` (POST/PUT) and `/sqltemporalstore/entry/delete` calls.
  * *Note: There may be a very large number of items in the SQL Temporal Store distributed over many EffectiveTimes. The system must only update the values that have changed.*

### D. Temporal Versioning (Effective Time Editing)
To allow items to move or change properties dynamically over time (e.g., a gate at coordinates `[1, 5]` on `2020-05-05` moving to coordinates `[5, 6]` on `2021-05-05`), the GWT UI supports managing multiple temporal versions of a single item:

1. **Effective Time History Table**:
   * The property form sidebar features an **Effective Times** grid showing all temporal versions defined for the selected item key.
   * Columns include: **Effective Time** (date-time string) and a **Summary** of the configuration at that time (e.g., `Coords: [1.0, 5.0], Name: Gate A`).
   * Below the grid, buttons allow the user to **Add Time**, **Edit Selected Time**, and **Delete Selected Time**.

2. **Temporal Editing Workflow**:
   When the user makes an edit on the canvas (dragging) or via the property fields:
   * **Exact Match**: If the currently selected timeline time matches the `effectiveTimeMs` of a version of this item exactly, the local changes are applied directly to that version's entry in GWT memory.
   * **No Exact Match**: If the selected timeline time falls *between* defined versions (or before the first version), the UI prompts the user with a dialog:
     * **Option A: "Modify Active Preceding Version"**: Modifies the fields of the latest version that was active prior to the selected time.
     * **Option B: "Create New Version at Selected Time"**: Clones the state of the active preceding version, assigns it the selected timeline time as its new `effectiveTimeMs`, and writes it as a new `TemporalEntry` version in memory. This represents a movement/change occurring precisely at the selected time.

3. **Effective Time Management Actions**:
   * **Add Time**: Prompts the user for a new timestamp, clones the current active item properties, and inserts a new temporal version in client memory.
   * **Edit Time**: Prompts the user to change the timestamp of the selected version, updating the entry's `effectiveTimeMs` key.
   * **Delete Time**: Removes the selected version of the item from client memory (marking it for deletion upon save). If this was the only version of the item, the entire item is removed.

---

## 7. Server-Side Implementation Details

To support the temporal item store model, the following changes are required on the server-side of Stroom:

### A. FloorMapDoc Entity Modifications
* **`temporalStoreRef` Field**: Update `FloorMapDoc.java` to store a reference to the selected `SqlTemporalStoreDoc`.
  * To avoid compile-time circular dependencies or tight coupling between GWT-shared modules, this property should be defined as a generic `DocRef` type:
    ```java
    @JsonProperty
    private final DocRef temporalStoreRef;
    ```
    During selection in the UI, the chooser will restrict selections to documents of type `"SqlTemporalStore"`.
* **Separate Events and Facts Queries**:
  * Since there are now two separate query tabs inside the document, replace the single `query`, `queryTimeRange`, and `queryTablePreferences` fields in `FloorMapDoc.java` with two distinct sets of fields:
    ```java
    @JsonProperty
    private final String eventsQuery;
    @JsonProperty
    private final TimeRange eventsQueryTimeRange;
    @JsonProperty
    private final QueryTablePreferences eventsQueryTablePreferences;

    @JsonProperty
    private final String factsQuery;
    @JsonProperty
    private final TimeRange factsQueryTimeRange;
    @JsonProperty
    private final QueryTablePreferences factsQueryTablePreferences;
    ```
* **Deprecate Inline Background Fields**: 
  * Remove `backgroundImages` (List of `FloorMapBackground`) and its associated getters/setters/builder fields from `FloorMapDoc.java`.
  * Remove the client-side/server-side helper `getActiveBackground()`.

### B. FloorMapStoreImpl Persistence
* Since background assets and plotted items are saved and CRUD-managed directly via the client-side REST services communicating with the SQL Temporal Store, the docstore handlers in `FloorMapStoreImpl.java` do not require any database synchronization logic.
* `FloorMapStoreImpl` will perform only standard XML/JSON document serialization of the `FloorMapDoc` (saving its name, the two queries, and the `temporalStoreRef` link).

### C. REST API and DAO Layer
* **Rest Resources**: `SqlTemporalStoreResourceImpl.java` and `UpdatableSqlTemporalStore.java` already expose endpoints for `create`, `update`, `fetch`, `delete`, and `find` actions. No changes are required in this layer as the client utilizes standard `DirectRestService` calls.
* **Database Schema**: The existing `UPDATABLE_TEMPORAL_STORE` table (and its corresponding jOOQ generated sources) is fully compatible and requires no alterations.

---

## 8. UI Comparison: Current vs. Proposed UI

The table below outlines the core differences between the currently implemented UI and the proposed design:

| Feature / UI Behavior | Currently Implemented UI | Proposed UI |
| :--- | :--- | :--- |
| **Editing Context** | Backgrounds are edited statically inside a separate **Settings Tab** using text forms and a grid. Plotted items cannot be created or edited. | Editing is done directly on the main **Map Tab** by toggling an **Edit Mode**. |
| **Canvas Interactions** | Map view is read-only. Panning and zooming are supported, but items cannot be moved. | Backgrounds and items can be translated, scaled, and rotated **directly on the canvas** via dragging and transformation handles. |
| **Sidebar Property Form** | None. | A collapsible details panel in Edit Mode allows point-and-click property edits, autocomplete dropdowns for item types (e.g., `"gates"`, `"computers"`), and an image selection tree. |
| **Item Management** | No support for adding/deleting mapped items. | sidebar controls to **Add Item** or **Delete Selected** items. |
| **Temporal Versioning** | A simple static "Valid From" table for background images in the Settings tab. | **Effective Times** grid for any selected item in the sidebar. Editing a dragged item or its fields prompts the user to either update the preceding version or spawn a new version at the selected timeline time (e.g., allowing a gate to move coordinates on a specific date). |
| **Storage & Persistence** | All backgrounds are stored inline inside the `FloorMapDoc` document. | Backgrounds/items are removed from `FloorMapDoc` and saved to the SQL Temporal Store database in delta-batches during save. |