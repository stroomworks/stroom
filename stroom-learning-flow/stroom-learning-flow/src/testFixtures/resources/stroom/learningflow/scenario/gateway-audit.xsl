<?xml version="1.0" encoding="UTF-8" ?>
<!--
  One event per record, whatever the record's element name: the children of <batch>, which have different
  names, are the records, so the templates match any child rather than a particular name. The context each
  record needs (the node, the site, and the generation time for a record with no time of its own) is on
  the enclosing <batch>, which the split keeps around each record.
-->
<xsl:stylesheet
    xmlns="event-logging:3"
    xpath-default-namespace="urn:example:gateway:audit:3"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0">

  <xsl:template match="export">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates select="batch/*"/>
    </Events>
  </xsl:template>

  <xsl:template match="batch/*">
    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="if (@at) then @at else ../@generated"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Tidewater Secure Exchange</Name>
          <Environment><xsl:value-of select="../@site"/></Environment>
        </System>
        <Generator>gateway-audit</Generator>
        <Device>
          <Name><xsl:value-of select="../@node"/></Name>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="local-name()"/></TypeId>
        <Process>
          <Action>Other</Action>
        </Process>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
