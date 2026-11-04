<?xml version="1.1" encoding="UTF-8"?>
<!-- Reference translation for pilot case 01. Written by hand to show the golden events are achievable and to
     check them. It is NOT shown to the model under test. -->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:r="records:2"
                xmlns="event-logging:3"
                exclude-result-prefixes="r xs">

  <xsl:output method="xml" indent="yes"/>

  <xsl:template match="/r:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="r:record[r:data[@name='action']]"/>
    </Events>
  </xsl:template>

  <xsl:template match="r:record">
    <xsl:variable name="v" select="."/>
    <xsl:variable name="ipv4" select="'^((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])$'"/>
    <xsl:variable name="src" select="r:data[@name='src']/@value"/>
    <xsl:variable name="dst" select="r:data[@name='dst']/@value"/>
    <xsl:variable name="time" select="r:data[@name='time']/@value"/>
    <xsl:variable name="srcOk" select="matches($src, $ipv4) or contains($src, ':')"/>
    <xsl:choose>
      <xsl:when test="not($srcOk)">
        <xsl:message>Invalid source address '<xsl:value-of select="$src"/>', record dropped</xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="action" select="r:data[@name='action']/@value"/>
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="format-dateTime(adjust-dateTime-to-timezone(xs:dateTime($time), xs:dayTimeDuration('PT0S')), '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].000Z')"/>
            </TimeCreated>
          </EventTime>
          <EventSource>
            <System><Name>Firewall</Name><Environment>Production</Environment></System>
            <Generator>Firewall</Generator>
            <Device><HostName><xsl:value-of select="r:data[@name='host']/@value"/></HostName></Device>
          </EventSource>
          <EventDetail>
            <TypeId><xsl:value-of select="if ($action = 'DROP') then 'NET_DENY' else 'NET_PERMIT'"/></TypeId>
            <Network>
              <xsl:element name="{if ($action = 'DROP') then 'Deny' else 'Permit'}">
                <Source>
                  <Device><IPAddress><xsl:value-of select="$src"/></IPAddress></Device>
                  <xsl:call-template name="proto-port"><xsl:with-param name="port" select="r:data[@name='sport']/@value"/></xsl:call-template>
                </Source>
                <xsl:if test="$dst != '-'">
                  <Destination>
                    <Device><IPAddress><xsl:value-of select="$dst"/></IPAddress></Device>
                    <xsl:call-template name="proto-port"><xsl:with-param name="port" select="r:data[@name='dport']/@value"/></xsl:call-template>
                  </Destination>
                </xsl:if>
              </xsl:element>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="proto-port">
    <xsl:param name="port"/>
    <xsl:variable name="p" select="upper-case(r:data[@name='proto']/@value)"/>
    <TransportProtocol><xsl:value-of select="if ($p = ('TCP','UDP','ICMP','IGMP')) then $p else 'Other'"/></TransportProtocol>
    <xsl:if test="$port != '-'"><Port><xsl:value-of select="$port"/></Port></xsl:if>
  </xsl:template>
</xsl:stylesheet>
