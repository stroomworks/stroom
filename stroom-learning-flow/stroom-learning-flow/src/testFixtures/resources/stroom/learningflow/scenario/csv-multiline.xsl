<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns="event-logging:3"
    xpath-default-namespace="records:2"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0">

  <xsl:template match="records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates/>
    </Events>
  </xsl:template>

  <!-- One record is one action on a document; the note is unquoted here, a doubled quote being one -->
  <xsl:template match="record">
    <xsl:variable name="raw" select="data[@name='note']/@value"/>
    <xsl:variable name="note" select="if (starts-with($raw, '&quot;'))
                                      then replace(substring($raw, 2, string-length($raw) - 2), '&quot;&quot;', '&quot;')
                                      else $raw"/>
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="data[@name='time']/@value"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Document store</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>Audit export</Generator>
        <Device>
          <Name><xsl:value-of select="data[@name='workstation']/@value"/></Name>
        </Device>
        <User>
          <Id><xsl:value-of select="data[@name='user']/@value"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="data[@name='action']/@value"/></TypeId>
        <xsl:variable name="object">
          <Document>
            <Name><xsl:value-of select="data[@name='document']/@value"/></Name>
          </Document>
          <Outcome>
            <Description><xsl:value-of select="$note"/></Description>
          </Outcome>
        </xsl:variable>
        <xsl:choose>
          <xsl:when test="data[@name='action']/@value = 'DELETE'">
            <Delete><xsl:copy-of select="$object"/></Delete>
          </xsl:when>
          <xsl:otherwise>
            <View><xsl:copy-of select="$object"/></View>
          </xsl:otherwise>
        </xsl:choose>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
