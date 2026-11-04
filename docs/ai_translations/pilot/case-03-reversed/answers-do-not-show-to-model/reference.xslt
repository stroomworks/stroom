<?xml version="1.1" encoding="UTF-8"?>
<!-- Reference translation for pilot case 03. NOT shown to the model under test.
     Fields (positional): 1 time (ISO, UTC), 2 host, 3 action, 4 DESTINATION address, 5 SOURCE address,
     6 destination port, 7 source port, 8 protocol. The log lists the destination first. -->
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
        <xsl:variable name="n" select="position()"/>
        <xsl:variable name="f" select="r:data/@value"/>
        <xsl:variable name="action" select="if ($f[3] = 'DENY') then 'Deny' else if ($f[3] = 'PERMIT') then 'Permit' else if ($f[3] = 'TEARDOWN') then 'Close' else ''"/>
        <xsl:variable name="ok" select="count($f) = 8 and $action != ''
            and (matches($f[4], $ipv4) or contains($f[4], ':')) and (matches($f[5], $ipv4) or contains($f[5], ':'))"/>
        <xsl:choose>
          <xsl:when test="not($ok)">
            <xsl:message>Record <xsl:value-of select="$n"/> dropped (invalid address or action): <xsl:value-of select="string-join($f, ',')"/></xsl:message>
          </xsl:when>
          <xsl:otherwise>
            <xsl:variable name="p" select="upper-case($f[8])"/>
            <xsl:variable name="proto" select="if ($p = ('TCP','UDP','ICMP','IGMP')) then $p else 'Other'"/>
            <Event>
              <EventTime><TimeCreated><xsl:value-of select="concat(substring($f[1],1,19),'.000Z')"/></TimeCreated></EventTime>
              <EventSource>
                <System><Name>Gateway</Name><Environment>Production</Environment></System>
                <Generator>Gateway</Generator>
                <Device><HostName><xsl:value-of select="$f[2]"/></HostName></Device>
              </EventSource>
              <EventDetail>
                <TypeId><xsl:value-of select="concat('GW-', $f[3])"/></TypeId>
                <Network>
                  <xsl:element name="{$action}">
                    <Source>
                      <Device><IPAddress><xsl:value-of select="$f[5]"/></IPAddress></Device>
                      <TransportProtocol><xsl:value-of select="$proto"/></TransportProtocol>
                      <xsl:if test="$f[7] != ''"><Port><xsl:value-of select="$f[7]"/></Port></xsl:if>
                    </Source>
                    <Destination>
                      <Device><IPAddress><xsl:value-of select="$f[4]"/></IPAddress></Device>
                      <TransportProtocol><xsl:value-of select="$proto"/></TransportProtocol>
                      <xsl:if test="$f[6] != ''"><Port><xsl:value-of select="$f[6]"/></Port></xsl:if>
                    </Destination>
                  </xsl:element>
                </Network>
              </EventDetail>
            </Event>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </Events>
  </xsl:template>
</xsl:stylesheet>
