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

  <!-- The BSD form has no year: the feed is known to be current, so the year is this one -->
  <xsl:template match="record">
    <xsl:variable name="months" select="('Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec')"/>
    <xsl:variable name="time">
      <xsl:choose>
        <xsl:when test="data[@name='time']">
          <xsl:value-of select="data[@name='time']/@value"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:value-of select="concat('2026-',
              format-number(index-of($months, data[@name='month']/@value), '00'), '-',
              format-number(xs:integer(data[@name='day']/@value), '00'), 'T',
              data[@name='clock']/@value, '.000Z')"/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:variable name="msg" select="data[@name='msg']"/>
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="$time"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Gateway SSH</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>syslog</Generator>
        <Device>
          <Name><xsl:value-of select="data[@name='host']/@value"/></Name>
        </Device>
        <Client>
          <IPAddress><xsl:value-of select="$msg/data[@name='ip']/@value"/></IPAddress>
          <Port><xsl:value-of select="$msg/data[@name='port']/@value"/></Port>
        </Client>
        <User>
          <Id><xsl:value-of select="$msg/data[@name='user']/@value"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="concat('sshd-', lower-case($msg/data[@name='outcome']/@value))"/></TypeId>
        <Authenticate>
          <Action>Logon</Action>
          <User>
            <Id><xsl:value-of select="$msg/data[@name='user']/@value"/></Id>
          </User>
          <Outcome>
            <Success><xsl:value-of select="$msg/data[@name='outcome']/@value = 'Accepted'"/></Success>
            <Description><xsl:value-of select="$msg/data[@name='method']/@value"/></Description>
          </Outcome>
        </Authenticate>
      </EventDetail>
    </Event>
  </xsl:template>
</xsl:stylesheet>
