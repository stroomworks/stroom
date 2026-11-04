<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:fn="http://www.w3.org/2005/xpath-functions"
                exclude-result-prefixes="rec xs fn">

  <xsl:variable name="ipv4" select="'^(25[0-5]|2[0-4]\d|[01]?\d?\d)(\.(25[0-5]|2[0-4]\d|[01]?\d?\d)){3}$'"/>

  <xsl:template match="/rec:records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      <!-- Only records with all 8 columns and a parseable date/time are valid. -->
      <xsl:apply-templates select="rec:record[count(rec:data) = 8]"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="time" select="normalize-space(rec:data[1]/@value)"/>
    <xsl:variable name="action" select="upper-case(normalize-space(rec:data[3]/@value))"/>
    <xsl:if test="matches($time, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')
                  and $action = ('ALLOW', 'BLOCK', 'RESET')">
      <Event>
        <EventTime>
          <!-- dd/MM/yyyy HH:mm:ss, assumed to be UTC -->
          <TimeCreated>
            <xsl:value-of select="concat(substring($time, 7, 4), '-', substring($time, 4, 2), '-',
                                         substring($time, 1, 2), 'T', substring($time, 12, 8), '.000Z')"/>
          </TimeCreated>
        </EventTime>
        <EventSource>
          <System>
            <Name>Firewall</Name>
            <Environment>Production</Environment>
          </System>
          <Generator>Firewall</Generator>
          <Device>
            <HostName><xsl:value-of select="normalize-space(rec:data[2]/@value)"/></HostName>
          </Device>
        </EventSource>
        <EventDetail>
          <TypeId>
            <xsl:value-of select="concat('Firewall-', $action)"/>
          </TypeId>
          <Description>
            <xsl:value-of select="concat('Firewall ', $action, ' of network traffic')"/>
          </Description>
          <Network>
            <xsl:variable name="body">
              <Source>
                <xsl:call-template name="location">
                  <xsl:with-param name="ip" select="rec:data[4]/@value"/>
                  <xsl:with-param name="port" select="rec:data[6]/@value"/>
                  <xsl:with-param name="proto" select="rec:data[8]/@value"/>
                </xsl:call-template>
              </Source>
              <Destination>
                <xsl:call-template name="location">
                  <xsl:with-param name="ip" select="rec:data[5]/@value"/>
                  <xsl:with-param name="port" select="rec:data[7]/@value"/>
                  <xsl:with-param name="proto" select="rec:data[8]/@value"/>
                </xsl:call-template>
              </Destination>
            </xsl:variable>
            <xsl:choose>
              <xsl:when test="$action = 'ALLOW'"><Permit><xsl:copy-of select="$body"/></Permit></xsl:when>
              <xsl:when test="$action = 'BLOCK'"><Deny><xsl:copy-of select="$body"/></Deny></xsl:when>
              <xsl:otherwise><Close><xsl:copy-of select="$body"/></Close></xsl:otherwise>
            </xsl:choose>
          </Network>
        </EventDetail>
      </Event>
    </xsl:if>
  </xsl:template>

  <!-- Builds the children of a NetworkLocation; sequence order: Device, TransportProtocol, Port. -->
  <xsl:template name="location">
    <xsl:param name="ip"/>
    <xsl:param name="port"/>
    <xsl:param name="proto"/>
    <xsl:variable name="addr" select="lower-case(normalize-space($ip))"/>
    <xsl:variable name="p" select="upper-case(normalize-space($proto))"/>
    <xsl:variable name="valid-ip"
                  select="matches($addr, $ipv4) or ($addr != '' and matches($addr, '^[0-9a-f:.]+$') and contains($addr, ':'))"/>
    <xsl:if test="$valid-ip">
      <Device>
        <IPAddress><xsl:value-of select="$addr"/></IPAddress>
      </Device>
    </xsl:if>
    <xsl:if test="$p != ''">
      <TransportProtocol>
        <xsl:value-of select="if ($p = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $p else 'Other'"/>
      </TransportProtocol>
    </xsl:if>
    <xsl:if test="matches(normalize-space($port), '^\d{1,5}$') and xs:integer(normalize-space($port)) le 65535">
      <Port><xsl:value-of select="xs:integer(normalize-space($port))"/></Port>
    </xsl:if>
  </xsl:template>
</xsl:stylesheet>
