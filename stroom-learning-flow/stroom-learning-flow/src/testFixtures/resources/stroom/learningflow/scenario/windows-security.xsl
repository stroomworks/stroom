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

  <!-- One Windows security event is one Event: the EventID names what happened, the EventData's named
       Data carry who, from where and what -->
  <xsl:template match="Event">
    <xsl:variable name="id" select="System/EventID"/>
    <xsl:variable name="data" select="EventData/Data"/>
    <xsl:variable name="user" select="if ($id = 4688) then $data[@Name='SubjectUserName'] else $data[@Name='TargetUserName']"/>
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
        <xsl:if test="$id = 4624 and $data[@Name='IpAddress'] != '-'">
          <Client>
            <IPAddress><xsl:value-of select="$data[@Name='IpAddress']"/></IPAddress>
            <Port><xsl:value-of select="$data[@Name='IpPort']"/></Port>
          </Client>
        </xsl:if>
        <User>
          <Id><xsl:value-of select="$user"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="$id"/></TypeId>
        <xsl:choose>
          <xsl:when test="$id = 4624">
            <Authenticate>
              <Action>Logon</Action>
              <LogonType>
                <xsl:choose>
                  <xsl:when test="$data[@Name='LogonType'] = 2">Interactive</xsl:when>
                  <xsl:when test="$data[@Name='LogonType'] = 3">Network</xsl:when>
                  <xsl:otherwise>Other</xsl:otherwise>
                </xsl:choose>
              </LogonType>
              <User>
                <Id><xsl:value-of select="$user"/></Id>
              </User>
              <Outcome>
                <Success>true</Success>
              </Outcome>
            </Authenticate>
          </xsl:when>
          <xsl:when test="$id = 4634">
            <Authenticate>
              <Action>Logoff</Action>
              <User>
                <Id><xsl:value-of select="$user"/></Id>
              </User>
            </Authenticate>
          </xsl:when>
          <xsl:otherwise>
            <Process>
              <Action>Execute</Action>
              <Type>OS</Type>
              <Command><xsl:value-of select="$data[@Name='NewProcessName']"/></Command>
              <Arguments><xsl:value-of select="$data[@Name='CommandLine']"/></Arguments>
              <ProcessId><xsl:value-of select="$data[@Name='NewProcessId']"/></ProcessId>
            </Process>
          </xsl:otherwise>
        </xsl:choose>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
