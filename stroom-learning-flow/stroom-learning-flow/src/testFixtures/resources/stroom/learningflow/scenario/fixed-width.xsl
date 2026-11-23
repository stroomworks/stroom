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

  <!-- One line is one sign-on decision: the columns are padded to their width, so each is trimmed -->
  <xsl:template match="record">
    <xsl:variable name="time" select="data[@name='time']/@value"/>
    <xsl:variable name="user" select="normalize-space(data[@name='user']/@value)"/>
    <xsl:variable name="terminal" select="normalize-space(data[@name='terminal']/@value)"/>
    <xsl:variable name="action" select="normalize-space(data[@name='action']/@value)"/>
    <xsl:variable name="result" select="normalize-space(data[@name='result']/@value)"/>
    <xsl:variable name="reason" select="normalize-space(data[@name='reason']/@value)"/>
    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="concat(substring($time, 1, 4), '-', substring($time, 5, 2), '-', substring($time, 7, 2),
                                       'T', substring($time, 9, 2), ':', substring($time, 11, 2), ':', substring($time, 13, 2),
                                       '.000Z')"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Mainframe security</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>Sign-on log</Generator>
        <Device>
          <Name><xsl:value-of select="$terminal"/></Name>
        </Device>
        <User>
          <Id><xsl:value-of select="$user"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="$action"/></TypeId>
        <Authenticate>
          <Action>
            <xsl:choose>
              <xsl:when test="$action = 'LOGOFF'">Logoff</xsl:when>
              <xsl:otherwise>Logon</xsl:otherwise>
            </xsl:choose>
          </Action>
          <User>
            <Id><xsl:value-of select="$user"/></Id>
          </User>
          <Outcome>
            <Success><xsl:value-of select="$result = 'GRANTED'"/></Success>
            <Description><xsl:value-of select="$reason"/></Description>
          </Outcome>
        </Authenticate>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
