<?xml version="1.1" encoding="UTF-8"?>
<!-- Reference translation for pilot case 02. Written by hand to show the golden events are achievable.
     It is NOT shown to the model under test. Fields are positional: 1 time (dd/MM/yyyy HH:mm:ss, UTC),
     2 host, 3 action, 4 first address (source), 5 second address (destination), 6 first port, 7 second port,
     8 protocol. -->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:r="records:2"
                xmlns="event-logging:3"
                exclude-result-prefixes="r xs">
  <xsl:output method="xml" indent="yes"/>

  <xsl:variable name="ipv4" select="'^((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])$'"/>

  <xsl:template match="/r:records">
    <Events Version="4.1.0">
      <xsl:for-each select="r:record">
        <xsl:call-template name="record"><xsl:with-param name="n" select="position()"/></xsl:call-template>
      </xsl:for-each>
    </Events>
  </xsl:template>

  <xsl:template name="record">
    <xsl:param name="n"/>
    <xsl:variable name="f" select="r:data/@value"/>
    <xsl:variable name="action" select="if ($f[3] = 'BLOCK') then 'Deny' else if ($f[3] = 'ALLOW') then 'Permit' else if ($f[3] = 'RESET') then 'Close' else ''"/>
    <xsl:variable name="ok" select="count($f) = 8 and $action != ''
        and ($f[4] castable as xs:string) and (matches($f[4], $ipv4) or contains($f[4], ':'))
        and (matches($f[5], $ipv4) or contains($f[5], ':'))"/>
    <xsl:choose>
      <xsl:when test="not($ok)">
        <xsl:message>Record <xsl:value-of select="$n"/> dropped (<xsl:value-of select="if (count($f) != 8) then 'wrong number of fields' else 'invalid address or action'"/>): <xsl:value-of select="string-join($f, ',')"/></xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="t" select="$f[1]"/>
        <xsl:variable name="iso" select="concat(substring($t,7,4),'-',substring($t,4,2),'-',substring($t,1,2),'T',substring($t,12,8),'.000Z')"/>
        <xsl:variable name="p" select="upper-case($f[8])"/>
        <Event>
          <EventTime><TimeCreated><xsl:value-of select="$iso"/></TimeCreated></EventTime>
          <EventSource>
            <System><Name>Firewall</Name><Environment>Production</Environment></System>
            <Generator>Firewall</Generator>
            <Device><HostName><xsl:value-of select="$f[2]"/></HostName></Device>
          </EventSource>
          <EventDetail>
            <TypeId><xsl:value-of select="concat('FW-', $f[3])"/></TypeId>
            <Network>
              <xsl:element name="{$action}">
                <Source>
                  <Device><IPAddress><xsl:value-of select="$f[4]"/></IPAddress></Device>
                  <TransportProtocol><xsl:value-of select="if ($p = ('TCP','UDP','ICMP','IGMP')) then $p else 'Other'"/></TransportProtocol>
                  <xsl:if test="$f[6] != ''"><Port><xsl:value-of select="$f[6]"/></Port></xsl:if>
                </Source>
                <Destination>
                  <Device><IPAddress><xsl:value-of select="$f[5]"/></IPAddress></Device>
                  <TransportProtocol><xsl:value-of select="if ($p = ('TCP','UDP','ICMP','IGMP')) then $p else 'Other'"/></TransportProtocol>
                  <xsl:if test="$f[7] != ''"><Port><xsl:value-of select="$f[7]"/></Port></xsl:if>
                </Destination>
              </xsl:element>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
</xsl:stylesheet>
