<?xml version="1.0" encoding="UTF-8" ?>
<!--
  Batch-boundary test harness. Each input row carries a `count`, and this emits that many
  <temporal-state> elements under the row's map name.

  Why: SqlStoreFilter buffers entries and flushes at WRITE_BATCH_SIZE (1000). What matters is the
  number of entries reaching the filter, not the number of input rows - so generating them here
  tests the boundary from a two-line CSV instead of a thousand-line one, and isolates the filter's
  buffering from CSV parsing.

  Keys vary by index, which is enough to keep every entry distinct - the upsert key includes the
  key - so one shared time serves all of them.

  The time must be ISO 8601. Both SqlStoreFilter and PlanBFilter parse <time> with
  DateUtil.parseNormalDateTimeStringToInstant, which does NOT accept epoch millis, whatever
  DateUtil.parseUnknownString elsewhere may allow.
-->
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns="reference-data:2"
    xmlns:records="records:2"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    exclude-result-prefixes="records xs"
    version="2.0">

  <xsl:output method="xml" indent="no"/>

  <xsl:template match="records:records">
    <referenceData>
      <xsl:apply-templates select="records:record"/>
    </referenceData>
  </xsl:template>

  <xsl:template match="records:record">
    <xsl:variable name="map" select="string(records:data[@name='map']/@value)"/>
    <xsl:variable name="prefix" select="string(records:data[@name='prefix']/@value)"/>
    <xsl:variable name="time" select="string(records:data[@name='time']/@value)"/>
    <xsl:variable name="count" select="xs:integer(records:data[@name='count']/@value)"/>

    <xsl:for-each select="1 to $count">
      <temporal-state>
        <map><xsl:value-of select="$map"/></map>
        <key><xsl:value-of select="concat($prefix, .)"/></key>
        <time><xsl:value-of select="$time"/></time>
        <value><xsl:value-of select="concat('{&quot;type&quot;:&quot;desk&quot;,&quot;n&quot;:', ., '}')"/></value>
      </temporal-state>
    </xsl:for-each>
  </xsl:template>

</xsl:stylesheet>
