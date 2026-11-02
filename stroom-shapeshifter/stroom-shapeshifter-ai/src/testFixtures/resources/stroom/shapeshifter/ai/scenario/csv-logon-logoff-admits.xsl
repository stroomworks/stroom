<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns="event-logging:3"
    xpath-default-namespace="records:2"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0">

  <xsl:template match="records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates/>
    </Events>
  </xsl:template>

  <!-- The admitted form (A53, A62): a learned Unknown, marked, for a record kind the model could not
       translate. Everything else is the logon-only stylesheet, kept as it was. -->
  <xsl:template match="record[not(data[@name='what']/@value = ('logon', 'logoff'))]">
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="data[@name='dt']/@value"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Door Access</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>CSV</Generator>
        <Device>
          <Name><xsl:value-of select="data[@name='where']/@value"/></Name>
        </Device>
        <User>
          <Id><xsl:value-of select="data[@name='who']/@value"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="data[@name='what']/@value"/></TypeId>
        <Unknown>
          <Data Name="ShapeshifterUnknown"><xsl:attribute name="Value" select="data[@name='what']/@value"/></Data>
        </Unknown>
      </EventDetail>
    </Event>
  </xsl:template>

  <xsl:template match="record">
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="data[@name='dt']/@value"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Door Access</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>CSV</Generator>
        <Device>
          <Name><xsl:value-of select="data[@name='where']/@value"/></Name>
        </Device>
        <User>
          <Id><xsl:value-of select="data[@name='who']/@value"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="data[@name='what']/@value"/></TypeId>
        <Authenticate>
          <Action>
            <xsl:choose>
              <xsl:when test="data[@name='what']/@value = 'logon'">Logon</xsl:when>
              <xsl:otherwise>Logoff</xsl:otherwise>
            </xsl:choose>
          </Action>
          <User>
            <Id><xsl:value-of select="data[@name='who']/@value"/></Id>
          </User>
        </Authenticate>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
