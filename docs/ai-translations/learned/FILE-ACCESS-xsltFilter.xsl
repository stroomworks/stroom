<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xpath-default-namespace="records:2">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates select="record"/>
    </Events>
  </xsl:template>

  <xsl:template match="record">
    <xsl:variable name="time" select="string(data[@name='time']/@value)"/>
    <xsl:variable name="user" select="string(data[@name='user']/@value)"/>
    <xsl:variable name="host" select="string(data[@name='host']/@value)"/>
    <xsl:variable name="action" select="upper-case(normalize-space(data[@name='action']/@value))"/>
    <xsl:variable name="file" select="string(data[@name='file']/@value)"/>
    <xsl:variable name="note" select="replace(string(data[@name='note']/@value), '&quot;&quot;', '&quot;')"/>

    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="$time"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>FILE-ACCESS</Name>
          <Environment>Unspecified</Environment>
        </System>
        <Generator>FILE-ACCESS</Generator>
        <Device>
          <HostName>
            <xsl:value-of select="$host"/>
          </HostName>
        </Device>
        <xsl:if test="$user != ''">
          <User>
            <Id>
              <xsl:value-of select="$user"/>
            </Id>
          </User>
        </xsl:if>
      </EventSource>
      <EventDetail>
        <xsl:choose>
          <xsl:when test="$action = 'VIEW'">
            <TypeId>FILE_VIEW</TypeId>
            <View>
              <File>
                <Name>
                  <xsl:value-of select="$file"/>
                </Name>
                <xsl:if test="$note != ''">
                  <Description>
                    <xsl:value-of select="$note"/>
                  </Description>
                </xsl:if>
              </File>
            </View>
          </xsl:when>
          <xsl:when test="$action = 'DELETE'">
            <TypeId>FILE_DELETE</TypeId>
            <Delete>
              <File>
                <Name>
                  <xsl:value-of select="$file"/>
                </Name>
                <xsl:if test="$note != ''">
                  <Description>
                    <xsl:value-of select="$note"/>
                  </Description>
                </xsl:if>
              </File>
            </Delete>
          </xsl:when>
          <xsl:otherwise>
            <xsl:message terminate="yes">
              <xsl:text>Unrecognised action: </xsl:text>
              <xsl:value-of select="$action"/>
            </xsl:message>
          </xsl:otherwise>
        </xsl:choose>
      </EventDetail>
    </Event>
  </xsl:template>

</xsl:stylesheet>
