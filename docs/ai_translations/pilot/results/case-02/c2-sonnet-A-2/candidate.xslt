<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:fn="http://www.w3.org/2005/xpath-functions"
                exclude-result-prefixes="rec xs fn">

  <xsl:variable name="ipv4" select="'^(25[0-5]|2[0-4]\d|[01]?\d?\d)(\.(25[0-5]|2[0-4]\d|[01]?\d?\d)){3}$'"/>
  <xsl:variable name="ipv6" select="'^[0-9a-fA-F:]*:[0-9a-fA-F:]*$'"/>

  <xsl:template match="/rec:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="d" select="rec:data/@value"/>
    <xsl:variable name="time" select="normalize-space($d[1])"/>
    <xsl:variable name="host" select="normalize-space($d[2])"/>
    <xsl:variable name="action" select="upper-case(normalize-space($d[3]))"/>
    <xsl:variable name="proto" select="upper-case(normalize-space($d[8]))"/>
    <!-- Records need all of time, host, action, source IP, destination IP and protocol. -->
    <xsl:if test="count($d) = 8
                  and matches($time, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')
                  and $host != ''
                  and $action = ('ALLOW', 'BLOCK', 'RESET')">
      <Event>
        <EventTime>
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
          <Generator>Firewall log</Generator>
          <Device>
            <HostName><xsl:value-of select="$host"/></HostName>
          </Device>
        </EventSource>
        <EventDetail>
          <TypeId>
            <xsl:value-of select="concat('Firewall', upper-case(substring($action, 1, 1)), lower-case(substring($action, 2)))"/>
          </TypeId>
          <Description>
            <xsl:choose>
              <xsl:when test="$action = 'ALLOW'">Firewall allowed a connection</xsl:when>
              <xsl:when test="$action = 'BLOCK'">Firewall blocked a connection</xsl:when>
              <xsl:otherwise>Firewall reset a connection</xsl:otherwise>
            </xsl:choose>
          </Description>
          <Network>
            <xsl:variable name="name" select="if ($action = 'ALLOW') then 'Permit' else if ($action = 'BLOCK') then 'Deny' else 'Close'"/>
            <xsl:element name="{$name}" namespace="event-logging:3">
              <Source>
                <xsl:call-template name="location">
                  <xsl:with-param name="ip" select="normalize-space($d[4])"/>
                  <xsl:with-param name="port" select="normalize-space($d[6])"/>
                  <xsl:with-param name="proto" select="$proto"/>
                </xsl:call-template>
              </Source>
              <Destination>
                <xsl:call-template name="location">
                  <xsl:with-param name="ip" select="normalize-space($d[5])"/>
                  <xsl:with-param name="port" select="normalize-space($d[7])"/>
                  <xsl:with-param name="proto" select="$proto"/>
                </xsl:call-template>
              </Destination>
            </xsl:element>
          </Network>
        </EventDetail>
      </Event>
    </xsl:if>
  </xsl:template>

  <xsl:template name="location">
    <xsl:param name="ip"/>
    <xsl:param name="port"/>
    <xsl:param name="proto"/>
    <xsl:variable name="validIp" select="matches($ip, $ipv4) or (matches($ip, $ipv6) and not(matches($ip, '[^0-9a-fA-F:]')))"/>
    <xsl:variable name="validPort" select="matches($port, '^\d+$') and xs:integer($port) le 65535"/>
    <xsl:if test="$validIp or $validPort">
      <Device>
        <xsl:if test="$validIp"><IPAddress><xsl:value-of select="lower-case($ip)"/></IPAddress></xsl:if>
        <xsl:if test="$validPort"><Port><xsl:value-of select="xs:integer($port)"/></Port></xsl:if>
        <xsl:if test="$ip != '' and not($validIp)">
          <Data Name="InvalidIPAddress" Value="{$ip}"/>
        </xsl:if>
      </Device>
    </xsl:if>
    <xsl:choose>
      <xsl:when test="$proto = ('TCP', 'UDP', 'ICMP', 'IGMP')">
        <TransportProtocol><xsl:value-of select="$proto"/></TransportProtocol>
      </xsl:when>
      <xsl:when test="$proto != ''"><TransportProtocol>Other</TransportProtocol></xsl:when>
    </xsl:choose>
  </xsl:template>
</xsl:stylesheet>
