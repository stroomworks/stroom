<?xml version="1.0" encoding="UTF-8" ?>
<!--
  Events CSV -> reference-data:2, for a Plan B TEMPORAL_STATE store.

  Same input and same output shape as floormap-facts.xslt - only the value schema differs, because
  the events query reads a different set of jq paths:

      jq(Value, '.location')  -> Location ID   (the only load-bearing one)
      jq(Value, '.type')      -> Event Type
      jq(Value, '.status')    -> Status
      jq(Value, '.message')   -> Message

  location takes either form:
    coordinates - "B-GND, 120.5, 340"  drawn exactly there
    a fact key  - "desk-114"           resolved against the facts store at the selected time,
                                       so moving the desk in the Editor moves its occupants

  A location naming a fact key that does not exist at the selected time is silently dropped -
  there is nowhere to draw it. The generated data uses both forms deliberately.
-->
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns="reference-data:2"
    xmlns:records="records:2"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:fm="urn:floormap-testdata"
    exclude-result-prefixes="records xs fm"
    version="2.0">

  <xsl:output method="xml" indent="yes"/>

  <xsl:function name="fm:str" as="xs:string">
    <xsl:param name="s" as="xs:string"/>
    <xsl:sequence select="concat('&quot;',
                                 replace(replace($s, '\\', '\\\\'), '&quot;', '\\&quot;'),
                                 '&quot;')"/>
  </xsl:function>

  <xsl:template match="records:records">
    <referenceData>
      <xsl:apply-templates select="records:record"/>
    </referenceData>
  </xsl:template>

  <xsl:template match="records:record">
    <xsl:variable name="location" select="string(records:data[@name='location']/@value)"/>
    <xsl:variable name="type" select="string(records:data[@name='type']/@value)"/>
    <xsl:variable name="status" select="string(records:data[@name='status']/@value)"/>
    <xsl:variable name="message" select="string(records:data[@name='message']/@value)"/>

    <xsl:variable name="members" as="xs:string*">
      <xsl:if test="$location != ''">
        <xsl:sequence select="concat('&quot;location&quot;:', fm:str($location))"/>
      </xsl:if>
      <xsl:if test="$type != ''">
        <xsl:sequence select="concat('&quot;type&quot;:', fm:str($type))"/>
      </xsl:if>
      <xsl:if test="$status != ''">
        <xsl:sequence select="concat('&quot;status&quot;:', fm:str($status))"/>
      </xsl:if>
      <xsl:if test="$message != ''">
        <xsl:sequence select="concat('&quot;message&quot;:', fm:str($message))"/>
      </xsl:if>
    </xsl:variable>

    <temporal-state>
      <!-- The Plan B document's name, which must match ^[a-z_0-9]+$. -->
      <map><xsl:value-of select="records:data[@name='map']/@value"/></map>
      <key><xsl:value-of select="records:data[@name='key']/@value"/></key>
      <!--
        Explicit per-event time, and for movement data this is not optional: omit it and every
        event in the stream lands at the stream's effective time, i.e. all at one instant, and
        playback has nothing to animate. With neither, ingest fails with
        "Temporal state 'time' is null".
      -->
      <time><xsl:value-of select="records:data[@name='time']/@value"/></time>
      <value><xsl:value-of select="concat('{', string-join($members, ','), '}')"/></value>
    </temporal-state>
  </xsl:template>

</xsl:stylesheet>
