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

  <!-- One record is one audit event: the lines that share a serial, each a data of its type -->
  <xsl:template match="record">
    <xsl:variable name="first" select="data[1]"/>
    <xsl:variable name="seconds" select="xs:decimal($first/data[@name='time']/@value)"/>
    <xsl:variable name="when" select="xs:dateTime('1970-01-01T00:00:00Z') + xs:dayTimeDuration(concat('PT', $seconds, 'S'))"/>
    <xsl:variable name="user" select="translate($first/data[@name='auid']/@value, '&quot;', '')"/>
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="format-dateTime($when, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Linux audit</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>auditd</Generator>
        <Device>
          <Name><xsl:value-of select="$first/data[@name='node']/@value"/></Name>
        </Device>
        <xsl:if test="$first/data[@name='addr']">
          <Client>
            <IPAddress><xsl:value-of select="$first/data[@name='addr']/@value"/></IPAddress>
          </Client>
        </xsl:if>
        <User>
          <Id><xsl:value-of select="$user"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="$first/@value"/></TypeId>
        <xsl:choose>
          <xsl:when test="$first/@value = 'USER_LOGIN'">
            <Authenticate>
              <Action>Logon</Action>
              <User>
                <Id><xsl:value-of select="$first/data[@name='id']/@value"/></Id>
              </User>
              <Outcome>
                <Success><xsl:value-of select="$first/data[@name='res']/@value = 'success'"/></Success>
                <Description><xsl:value-of select="$first/data[@name='terminal']/@value"/></Description>
              </Outcome>
            </Authenticate>
          </xsl:when>
          <xsl:otherwise>
            <xsl:variable name="execve" select="data[@value='EXECVE']"/>
            <Process>
              <Action>Execute</Action>
              <Type>OS</Type>
              <Command><xsl:value-of select="translate($execve/data[@name='a0']/@value, '&quot;', '')"/></Command>
              <xsl:if test="$execve/data[@name='a1']">
                <Arguments>
                  <xsl:value-of select="string-join(for $a in $execve/data[matches(@name, '^a[1-9]')] return translate($a/@value, '&quot;', ''), ' ')"/>
                </Arguments>
              </xsl:if>
              <ProcessId><xsl:value-of select="$first/data[@name='pid']/@value"/></ProcessId>
            </Process>
          </xsl:otherwise>
        </xsl:choose>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
