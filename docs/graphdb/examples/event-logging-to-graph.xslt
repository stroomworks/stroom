<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:evt="event-logging:3"
                xmlns:gm="graph-mutation:1"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns="graph-mutation:1"
                exclude-result-prefixes="evt xs gm">

  <xsl:output method="xml" indent="yes"/>

  <!-- ============================================================
       Stroom event-logging:3 (v4.1.0)  ->  graph-mutation:1

       Model: event-as-edge. Each event contributes edges between
       long-lived entity nodes, rather than a node per event.
       ============================================================ -->

  <xsl:template match="/evt:Events">
    <!-- xsi:schemaLocation is required, not decorative: a SchemaFilter placed before the Graph Filter rejects a
         document that does not declare where its namespace's schema is, so a translation that omits it cannot be
         validated at all. The system id must match the one the XMLSchema document is registered under. -->
    <graph version="1.0"
           xsi:schemaLocation="graph-mutation:1 graph-mutation-v1.0.xsd">
      <!-- Pass 1: every node reference any event implies. -->
      <xsl:variable name="refs" as="element(gm:ref)*">
        <xsl:apply-templates select="evt:Event" mode="nodes"/>
      </xsl:variable>

      <!-- Emit each distinct node once, at the earliest time it was seen. -->
      <xsl:for-each-group select="$refs" group-by="@id">
        <xsl:sort select="current-grouping-key()"/>
        <xsl:variable name="earliest"
                      select="min(for $t in current-group()/@ts return xs:string($t))"/>
        <node id="{current-grouping-key()}" validFrom="{$earliest}">
          <xsl:for-each select="distinct-values(current-group()/@label)">
            <label><xsl:value-of select="."/></label>
          </xsl:for-each>
          <xsl:for-each-group select="current-group()/gm:p" group-by="@n">
            <property name="{current-grouping-key()}">
              <xsl:value-of select="current-group()[1]"/>
            </property>
          </xsl:for-each-group>
        </node>
      </xsl:for-each-group>

      <!-- Pass 2: the edges. -->
      <xsl:apply-templates select="evt:Event" mode="edges"/>
    </graph>
  </xsl:template>

  <!-- ============================================================
       Helpers
       ============================================================ -->

  <!-- Normalise any event-logging dateTime to the exact form the
       Graph Filter demands: yyyy-MM-ddThh:mm:ss.sssZ (UTC, 3 digits). -->
  <xsl:template name="graph-timestamp" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:variable name="utc"
                  select="adjust-dateTime-to-timezone(xs:dateTime($value),
                                                      xs:dayTimeDuration('PT0S'))"/>
    <xsl:value-of
        select="format-dateTime($utc, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
  </xsl:template>

  <!-- A stable, deterministic node id. Derived only from natural keys. -->
  <xsl:template name="stable-id" as="xs:string">
    <xsl:param name="kind" as="xs:string"/>
    <xsl:param name="key" as="xs:string"/>
    <xsl:value-of select="concat($kind, ':', lower-case(normalize-space($key)))"/>
  </xsl:template>

  <!-- The event's time, as every node and edge it produces must carry it. -->
  <xsl:function name="gm:when" as="xs:string">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:variable name="utc"
                  select="adjust-dateTime-to-timezone(
                            xs:dateTime($event/evt:EventTime/evt:TimeCreated),
                            xs:dayTimeDuration('PT0S'))"/>
    <xsl:value-of
        select="format-dateTime($utc, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
  </xsl:function>

  <!-- Identity of a device: prefer host name, fall back to IP. -->
  <xsl:function name="gm:device-id" as="xs:string?">
    <xsl:param name="device" as="element()?"/>
    <xsl:sequence select="if (empty($device)) then ()
                          else if ($device/evt:HostName)
                          then concat('device:', lower-case(normalize-space($device/evt:HostName)))
                          else if ($device/evt:IPAddress)
                          then concat('device:', lower-case(normalize-space($device/evt:IPAddress)))
                          else ()"/>
  </xsl:function>

  <xsl:function name="gm:user-id" as="xs:string?">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:variable name="u" select="$event/evt:EventSource/evt:User/evt:Id"/>
    <xsl:sequence select="if ($u) then concat('user:', lower-case(normalize-space($u))) else ()"/>
  </xsl:function>

  <xsl:function name="gm:session-id" as="xs:string?">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:variable name="s" select="$event/evt:EventSource/evt:SessionId"/>
    <xsl:sequence select="if ($s) then concat('session:', normalize-space($s)) else ()"/>
  </xsl:function>

  <xsl:function name="gm:app-id" as="xs:string?">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:variable name="n" select="$event/evt:EventSource/evt:System/evt:Name"/>
    <xsl:sequence select="if ($n) then concat('app:', lower-case(normalize-space($n))) else ()"/>
  </xsl:function>

  <!-- The files an event acts on, whatever the action. -->
  <xsl:function name="gm:files" as="element(evt:File)*">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:sequence select="$event/evt:EventDetail//evt:File"/>
  </xsl:function>

  <xsl:function name="gm:file-id" as="xs:string">
    <xsl:param name="file" as="element(evt:File)"/>
    <xsl:sequence select="concat('file:', lower-case(normalize-space($file/evt:Path)))"/>
  </xsl:function>

  <!-- The action word for an ACCESSED edge, from the EventDetail child name. -->
  <xsl:function name="gm:action" as="xs:string">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:variable name="verb"
                  select="local-name(($event/evt:EventDetail/*[local-name() = (
                            'View','Create','Update','Delete','Copy','Move',
                            'Import','Export','Print')])[1])"/>
    <xsl:sequence select="if ($verb) then upper-case($verb) else 'UNKNOWN'"/>
  </xsl:function>

  <xsl:function name="gm:outcome" as="xs:string">
    <xsl:param name="event" as="element(evt:Event)"/>
    <xsl:variable name="s" select="($event/evt:EventDetail//evt:Outcome/evt:Success)[1]"/>
    <xsl:sequence select="if ($s = 'false') then 'FAILURE' else 'SUCCESS'"/>
  </xsl:function>

  <!-- ============================================================
       Pass 1 - node references
       ============================================================ -->

  <xsl:template match="evt:Event" mode="nodes">
    <xsl:variable name="ts" select="gm:when(.)"/>

    <xsl:if test="gm:user-id(.)">
      <gm:ref id="{gm:user-id(.)}" label="User" ts="{$ts}">
        <gm:p n="id"><xsl:value-of select="evt:EventSource/evt:User/evt:Id"/></gm:p>
      </gm:ref>
    </xsl:if>

    <xsl:for-each select="evt:EventSource/(evt:Device | evt:Client | evt:Server)">
      <xsl:if test="gm:device-id(.)">
        <gm:ref id="{gm:device-id(.)}" label="Device" ts="{$ts}">
          <xsl:if test="evt:HostName">
            <gm:p n="hostName"><xsl:value-of select="evt:HostName"/></gm:p>
          </xsl:if>
          <xsl:if test="evt:IPAddress">
            <gm:p n="ipAddress"><xsl:value-of select="evt:IPAddress"/></gm:p>
          </xsl:if>
        </gm:ref>
      </xsl:if>
    </xsl:for-each>

    <xsl:if test="gm:session-id(.)">
      <gm:ref id="{gm:session-id(.)}" label="Session" ts="{$ts}">
        <gm:p n="sessionId"><xsl:value-of select="evt:EventSource/evt:SessionId"/></gm:p>
      </gm:ref>
    </xsl:if>

    <xsl:if test="gm:app-id(.)">
      <gm:ref id="{gm:app-id(.)}" label="Application" ts="{$ts}">
        <gm:p n="name"><xsl:value-of select="evt:EventSource/evt:System/evt:Name"/></gm:p>
        <xsl:if test="evt:EventSource/evt:System/evt:Environment">
          <gm:p n="environment">
            <xsl:value-of select="evt:EventSource/evt:System/evt:Environment"/>
          </gm:p>
        </xsl:if>
      </gm:ref>
    </xsl:if>

    <xsl:for-each select="gm:files(.)">
      <gm:ref id="{gm:file-id(.)}" label="File" ts="{$ts}">
        <gm:p n="path"><xsl:value-of select="evt:Path"/></gm:p>
        <xsl:if test="evt:Name">
          <gm:p n="name"><xsl:value-of select="evt:Name"/></gm:p>
        </xsl:if>
      </gm:ref>
    </xsl:for-each>
  </xsl:template>

  <!-- ============================================================
       Pass 2 - edges, dispatched on the EventDetail child
       ============================================================ -->

  <xsl:template match="evt:Event" mode="edges">
    <xsl:variable name="ts"      select="gm:when(.)"/>
    <xsl:variable name="user"    select="gm:user-id(.)"/>
    <xsl:variable name="device"  select="gm:device-id(evt:EventSource/evt:Device)"/>
    <xsl:variable name="session" select="gm:session-id(.)"/>
    <xsl:variable name="app"     select="gm:app-id(.)"/>

    <!-- Every event ties its user to the application it was generated by. -->
    <xsl:if test="$user and $app">
      <xsl:call-template name="emit-edge">
        <xsl:with-param name="type" select="'USED'"/>
        <xsl:with-param name="src"  select="$user"/>
        <xsl:with-param name="dst"  select="$app"/>
        <xsl:with-param name="ts"   select="$ts"/>
      </xsl:call-template>
    </xsl:if>

    <xsl:apply-templates select="evt:EventDetail" mode="edges">
      <xsl:with-param name="ts"      select="$ts"/>
      <xsl:with-param name="user"    select="$user"/>
      <xsl:with-param name="device"  select="$device"/>
      <xsl:with-param name="session" select="$session"/>
      <xsl:with-param name="event"   select="."/>
    </xsl:apply-templates>
  </xsl:template>

  <!-- Authenticate: user logs on to a device, opening a session. -->
  <xsl:template match="evt:EventDetail[evt:Authenticate]" mode="edges">
    <xsl:param name="ts"/>
    <xsl:param name="user"/>
    <xsl:param name="device"/>
    <xsl:param name="session"/>
    <xsl:param name="event"/>

    <xsl:if test="$user and $device">
      <xsl:call-template name="emit-edge">
        <xsl:with-param name="type" select="'AUTHENTICATED_ON'"/>
        <xsl:with-param name="src"  select="$user"/>
        <xsl:with-param name="dst"  select="$device"/>
        <xsl:with-param name="ts"   select="$ts"/>
        <xsl:with-param name="props" as="element(gm:p)*">
          <gm:p n="outcome"><xsl:value-of select="gm:outcome($event)"/></gm:p>
        </xsl:with-param>
      </xsl:call-template>
    </xsl:if>

    <xsl:if test="$user and $session">
      <xsl:call-template name="emit-edge">
        <xsl:with-param name="type" select="'STARTED_SESSION'"/>
        <xsl:with-param name="src"  select="$user"/>
        <xsl:with-param name="dst"  select="$session"/>
        <xsl:with-param name="ts"   select="$ts"/>
      </xsl:call-template>
    </xsl:if>

    <xsl:if test="$session and $device">
      <xsl:call-template name="emit-edge">
        <xsl:with-param name="type" select="'SESSION_ON'"/>
        <xsl:with-param name="src"  select="$session"/>
        <xsl:with-param name="dst"  select="$device"/>
        <xsl:with-param name="ts"   select="$ts"/>
      </xsl:call-template>
    </xsl:if>
  </xsl:template>

  <!-- Any file-touching action: View, Create, Update, Delete, ... -->
  <xsl:template match="evt:EventDetail[.//evt:File]" mode="edges" priority="2">
    <xsl:param name="ts"/>
    <xsl:param name="user"/>
    <xsl:param name="device"/>
    <xsl:param name="event"/>

    <xsl:for-each select="gm:files($event)">
      <xsl:variable name="file" select="gm:file-id(.)"/>

      <xsl:if test="$user">
        <xsl:call-template name="emit-edge">
          <xsl:with-param name="type" select="'ACCESSED'"/>
          <xsl:with-param name="src"  select="$user"/>
          <xsl:with-param name="dst"  select="$file"/>
          <xsl:with-param name="ts"   select="$ts"/>
          <xsl:with-param name="props" as="element(gm:p)*">
            <gm:p n="action"><xsl:value-of select="gm:action($event)"/></gm:p>
            <gm:p n="outcome"><xsl:value-of select="gm:outcome($event)"/></gm:p>
          </xsl:with-param>
        </xsl:call-template>
      </xsl:if>

      <xsl:if test="$device">
        <xsl:call-template name="emit-edge">
          <xsl:with-param name="type" select="'HOSTED_ON'"/>
          <xsl:with-param name="src"  select="$file"/>
          <xsl:with-param name="dst"  select="$device"/>
          <xsl:with-param name="ts"   select="$ts"/>
        </xsl:call-template>
      </xsl:if>
    </xsl:for-each>
  </xsl:template>

  <!-- Network: client talks to server. -->
  <xsl:template match="evt:EventDetail[evt:Network]" mode="edges">
    <xsl:param name="ts"/>
    <xsl:param name="event"/>

    <xsl:variable name="client" select="gm:device-id($event/evt:EventSource/evt:Client)"/>
    <xsl:variable name="server" select="gm:device-id($event/evt:EventSource/evt:Server)"/>

    <xsl:if test="$client and $server">
      <xsl:call-template name="emit-edge">
        <xsl:with-param name="type" select="'CONNECTED_TO'"/>
        <xsl:with-param name="src"  select="$client"/>
        <xsl:with-param name="dst"  select="$server"/>
        <xsl:with-param name="ts"   select="$ts"/>
      </xsl:call-template>
    </xsl:if>
  </xsl:template>

  <!-- Fall-through: an event type this translation does not model.
       Emits nothing. See the doc for how to find these. -->
  <xsl:template match="evt:EventDetail" mode="edges">
    <xsl:param name="ts"/>
  </xsl:template>

  <!-- ============================================================
       Emitters
       ============================================================ -->

  <xsl:template name="emit-edge">
    <xsl:param name="type"  as="xs:string"/>
    <xsl:param name="src"   as="xs:string"/>
    <xsl:param name="dst"   as="xs:string"/>
    <xsl:param name="ts"    as="xs:string"/>
    <xsl:param name="props" as="element(gm:p)*" select="()"/>
    <edge type="{$type}" validFrom="{$ts}">
      <src><xsl:value-of select="$src"/></src>
      <dst><xsl:value-of select="$dst"/></dst>
      <xsl:for-each select="$props">
        <property name="{@n}"><xsl:value-of select="."/></property>
      </xsl:for-each>
    </edge>
  </xsl:template>

</xsl:stylesheet>
