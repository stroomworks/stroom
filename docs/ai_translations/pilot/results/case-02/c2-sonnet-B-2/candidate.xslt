<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                exclude-result-prefixes="rec xs">

  <xsl:template match="/rec:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="d" select="rec:data/@value"/>
    <xsl:variable name="action" select="upper-case(normalize-space($d[3]))"/>
    <xsl:variable name="srcIp" select="normalize-space($d[4])"/>
    <xsl:variable name="dstIp" select="normalize-space($d[5])"/>
    <xsl:variable name="ts" select="normalize-space($d[1])"/>
    <xsl:variable name="ipv4" select="'^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$'"/>
    <xsl:variable name="ipv6" select="'^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$'"/>

    <xsl:choose>
      <xsl:when test="count($d) lt 8">
        <xsl:message>Dropping record with too few fields (<xsl:value-of select="count($d)"/> of 8): <xsl:value-of select="string-join($d, ',')"/></xsl:message>
      </xsl:when>
      <xsl:when test="not(matches($ts, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$'))">
        <xsl:message>Dropping record with invalid timestamp: <xsl:value-of select="string-join($d, ',')"/></xsl:message>
      </xsl:when>
      <xsl:when test="not(matches($srcIp, $ipv4) or matches($srcIp, $ipv6)) or not(matches($dstIp, $ipv4) or matches($dstIp, $ipv6))">
        <xsl:message>Dropping record with invalid IP address: <xsl:value-of select="string-join($d, ',')"/></xsl:message>
      </xsl:when>
      <xsl:when test="not($action = ('BLOCK', 'ALLOW', 'RESET'))">
        <xsl:message>Dropping record with unknown action '<xsl:value-of select="$action"/>': <xsl:value-of select="string-join($d, ',')"/></xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="proto" select="upper-case(normalize-space($d[8]))"/>
        <xsl:variable name="transport" select="if ($proto = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $proto else 'Other'"/>
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="concat(substring($ts, 7, 4), '-', substring($ts, 4, 2), '-', substring($ts, 1, 2), 'T', substring($ts, 12, 8), '.000Z')"/>
            </TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>Firewall</Name><Environment>Unknown</Environment>
            </System>
            <Generator>Firewall</Generator>
            <Device>
              <HostName><xsl:value-of select="normalize-space($d[2])"/></HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId>
              <xsl:value-of select="concat('Network-', $action)"/>
            </TypeId>
            <Description>
              <xsl:value-of select="concat('Firewall ', $action, ' of ', $transport, ' traffic')"/>
            </Description>
            <Network>
              <xsl:variable name="body">
                <Source>
                  <Device><IPAddress><xsl:value-of select="$srcIp"/></IPAddress></Device>
                  <TransportProtocol><xsl:value-of select="$transport"/></TransportProtocol>
                  <xsl:call-template name="port"><xsl:with-param name="p" select="$d[6]"/></xsl:call-template>
                </Source>
                <Destination>
                  <Device><IPAddress><xsl:value-of select="$dstIp"/></IPAddress></Device>
                  <TransportProtocol><xsl:value-of select="$transport"/></TransportProtocol>
                  <xsl:call-template name="port"><xsl:with-param name="p" select="$d[7]"/></xsl:call-template>
                </Destination>
              </xsl:variable>
              <xsl:choose>
                <xsl:when test="$action = 'BLOCK'"><Deny><xsl:copy-of select="$body"/></Deny></xsl:when>
                <xsl:when test="$action = 'ALLOW'"><Permit><xsl:copy-of select="$body"/></Permit></xsl:when>
                <xsl:otherwise><Close><xsl:copy-of select="$body"/></Close></xsl:otherwise>
              </xsl:choose>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="port">
    <xsl:param name="p"/>
    <xsl:if test="matches(normalize-space($p), '^\d+$')">
      <Port><xsl:value-of select="normalize-space($p)"/></Port>
    </xsl:if>
  </xsl:template>
</xsl:stylesheet>
