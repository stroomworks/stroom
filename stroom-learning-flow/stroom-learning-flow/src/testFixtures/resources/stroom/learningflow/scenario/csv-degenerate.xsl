<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns="event-logging:3"
    xpath-default-namespace="records:2"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0">

  <!-- The degeneracy trap of design 01 §8.3: the mandatory skeleton, Unknown where a decision was due, and
       every field dumped into untyped Data. It validates. It extracts nothing. -->
  <xsl:template match="records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates/>
    </Events>
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
          <Name>unknown</Name>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId>record</TypeId>
        <Unknown>
          <xsl:for-each select="data">
            <Data Name="{@name}" Value="{@value}"/>
          </xsl:for-each>
        </Unknown>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
