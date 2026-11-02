<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns="event-logging:3"
    xpath-default-namespace="http://www.w3.org/2013/XSL/json"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0">

  <xsl:template match="/">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <!-- One record is one object with a time: a top-level value in JSON lines, an item of the events
           array in the document form -->
      <xsl:apply-templates select="//map[string/@key = 'time']"/>
    </Events>
  </xsl:template>

  <xsl:template match="map">
    <xsl:variable name="user" select="string[@key = 'user']"/>
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="string[@key = 'time']"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>API Gateway</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>JSON</Generator>
        <Device>
          <Name><xsl:value-of select="string[@key = 'host']"/></Name>
        </Device>
        <Client>
          <IPAddress><xsl:value-of select="map[@key = 'client']/string[@key = 'ip']"/></IPAddress>
          <Port><xsl:value-of select="map[@key = 'client']/number[@key = 'port']"/></Port>
        </Client>
        <User>
          <Id><xsl:value-of select="$user"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="string[@key = 'kind']"/></TypeId>
        <Authenticate>
          <Action>
            <xsl:choose>
              <xsl:when test="string[@key = 'kind'] = 'login'">Logon</xsl:when>
              <xsl:otherwise>Logoff</xsl:otherwise>
            </xsl:choose>
          </Action>
          <User>
            <Id><xsl:value-of select="$user"/></Id>
          </User>
          <xsl:if test="string[@key = 'outcome']">
            <Outcome>
              <Success><xsl:value-of select="string[@key = 'outcome'] = 'success'"/></Success>
            </Outcome>
          </xsl:if>
        </Authenticate>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
