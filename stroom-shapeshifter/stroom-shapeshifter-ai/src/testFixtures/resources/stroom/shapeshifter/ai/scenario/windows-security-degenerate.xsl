<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns="event-logging:3"
    xpath-default-namespace="http://schemas.microsoft.com/win/2004/08/events/event"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0">

  <xsl:template match="/">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates select="//Event"/>
    </Events>
  </xsl:template>

  <!-- The degeneracy trap in the wild: every Data copied to a Data under Unknown, the EventID as the type;
       it validates and says nothing -->
  <xsl:template match="Event">
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="concat(substring(System/TimeCreated/@SystemTime, 1, 23), 'Z')"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Windows Security</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>Microsoft-Windows-Security-Auditing</Generator>
        <Device>
          <Name><xsl:value-of select="System/Computer"/></Name>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="System/EventID"/></TypeId>
        <Unknown>
          <xsl:for-each select="EventData/Data">
            <Data Name="{@Name}" Value="{.}"/>
          </xsl:for-each>
        </Unknown>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
