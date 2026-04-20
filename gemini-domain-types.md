This GIT branch contains an initial implementation of a basic type system for Stroom.
The relevant code uses the name 'domainType' for the String containing the type name. 
This allows users to link searches across dashboards by means of context menu items.

I am considering whether this type system should be expanded to incorporate a 'Class.AttributeType' structure.

While the data within Stroom follows the Event Logging Schema (can be found here: https://raw.githubusercontent.com/stroomworks/stroom-content/refs/heads/master/source/event-logging-xml-schema/stroomContent/XML%20Schemas/event-logging/event_logging_v4_1_0.XMLSchema.befcc474-36e4-4db4-a610-bd1fe6825cb1.data.xsd), the data in Indexes always follows this much simpler records schema: https://raw.githubusercontent.com/stroomworks/stroom-content/refs/heads/master/source/core-xml-schemas/stroomContent/XML%20Schemas/records/records_v2_0.XMLSchema.47f062d9-8191-4535-b35b-74c6f020320f.data.xsd. 
Data can only be shown and examined in a Dashboard within Stroom in the records format as it must fit into a table.

Thus, most of the data actually in use within Stroom follows the Object.Attribute format.

In addition, I want to be able to link the data within Stroom into data within a Graph Database such as Neo4j. 
Data within a Graph Database follows the Object.Attribute pattern, although it also has relationships too.

Therefore, the type system within Stroom could follow the Class.AttributeType notation.
Anything in the Events XML schema would probably have a class of Event; for example mapping XPath element to type:
- /Events/Event/EventTime/TimeCreated : Event.Time
- /Events/Event/EventSource/User/Id : Event.userId 
- /Events/Event/EventDetail/View/Document/Id : Event.documentId
- /Events/Event/EventDetail/Update/After/Resource/Name : Event.documentId
- /Events/Event/EventDetail/Delete/File/Path : Event.documentId
- /Events/Event/EventDetail/Create/File/Path : Event.documentId

The matching to Dashboards could then permit the use of wildcards for Class and/or AttributeType. 
Thus, a Dashboard that accepts a type of userId could be marked as *.userId, so it can accept a userId from any Class.

We can also allow aliases. These will be necessary to merge type systems together.
One content pack might have a type of uId, while another might use userId. In these cases the system needs to 
know that it can map across these types.

Aliases could use wildcards. 
The system wouldn't know of a way to find Event.*, so this would be aliased to Event.Time as a way to identify that event.

