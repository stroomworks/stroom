<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:f="urn:local:functions"
                exclude-result-prefixes="rec xs f">

  <!-- Columns: 1 time, 2 device, 3 action, 4 src ip, 5 dst ip, 6 src port, 7 dst port, 8 protocol -->

  <xsl:function name="f:valid-ipv4" as="xs:boolean">
    <xsl:param name="v" as="xs:string"/>
    <xsl:sequence select="matches($v, '^((25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])$')"/>
  </xsl:function>

  <!-- Accept IPv4, or IPv6 made of hex groups and colons (at most one '::'). -->
  <xsl:function name="f:valid-ip" as="xs:boolean">
    <xsl:param name="v" as="xs:string"/>
    <xsl:sequence select="f:valid-ipv4($v)
      or (matches($v, '^[0-9a-fA-F:]+$') and contains($v, ':')
          and not(contains($v, ':::'))
          and count(tokenize($v, '::')) le 2
          and (every $g in tokenize($v, ':') satisfies string-length($g) le 4)
          and (if (contains($v, '::')) then count(tokenize($v, ':')[. != '']) le 7
               else count(tokenize($v, ':')) = 8))"/>
  </xsl:function>

  <xsl:function name="f:valid-port" as="xs:boolean">
    <xsl:param name="v" as="xs:string"/>
    <xsl:sequence select="matches($v, '^[0-9]{1,5}$') and xs:integer($v) le 65535"/>
  </xsl:function>

  <xsl:function name="f:valid-time" as="xs:boolean">
    <xsl:param name="v" as="xs:string"/>
    <xsl:sequence select="matches($v, '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$')
                          and ($v castable as xs:dateTime)"/>
  </xsl:function>

  <xsl:template match="/rec:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="d" select="rec:data/@value"/>
    <xsl:variable name="time" select="normalize-space($d[1])"/>
    <xsl:variable name="device" select="normalize-space($d[2])"/>
    <xsl:variable name="action" select="upper-case(normalize-space($d[3]))"/>
    <xsl:variable name="srcIp" select="lower-case(normalize-space($d[4]))"/>
    <xsl:variable name="dstIp" select="lower-case(normalize-space($d[5]))"/>
    <xsl:variable name="srcPort" select="normalize-space($d[6])"/>
    <xsl:variable name="dstPort" select="normalize-space($d[7])"/>
    <xsl:variable name="proto" select="upper-case(normalize-space($d[8]))"/>

    <!-- Records that cannot be represented validly are dropped. -->
    <xsl:if test="count($d) = 8
                  and f:valid-time($time)
                  and $device != ''
                  and $action = ('DENY', 'PERMIT', 'TEARDOWN')
                  and f:valid-ip($srcIp) and f:valid-ip($dstIp)
                  and ($srcPort = '' or f:valid-port($srcPort))
                  and ($dstPort = '' or f:valid-port($dstPort))
                  and $proto = ('TCP', 'UDP', 'ICMP', 'IGMP')">
      <Event>
        <EventTime>
          <TimeCreated>
            <xsl:value-of select="format-dateTime(
              adjust-dateTime-to-timezone(xs:dateTime($time), xs:dayTimeDuration('PT0H')),
              '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
          </TimeCreated>
        </EventTime>
        <EventSource>
          <System>
            <Name>Firewall</Name>
            <Environment>Production</Environment>
          </System>
          <Generator>Firewall</Generator>
          <Device>
            <HostName><xsl:value-of select="$device"/></HostName>
          </Device>
        </EventSource>
        <EventDetail>
          <TypeId>
            <xsl:value-of select="concat('Firewall-', $action)"/>
          </TypeId>
          <Network>
            <xsl:variable name="outcome">
              <xsl:if test="$action != 'TEARDOWN'">
                <Outcome>
                  <Permitted><xsl:value-of select="$action = 'PERMIT'"/></Permitted>
                </Outcome>
              </xsl:if>
            </xsl:variable>
            <xsl:element name="{if ($action = 'DENY') then 'Deny' else if ($action = 'PERMIT') then 'Permit' else 'Close'}">
              <Source>
                <Device><IPAddress><xsl:value-of select="$srcIp"/></IPAddress></Device>
                <TransportProtocol><xsl:value-of select="$proto"/></TransportProtocol>
                <xsl:if test="$srcPort != ''"><Port><xsl:value-of select="xs:integer($srcPort)"/></Port></xsl:if>
              </Source>
              <Destination>
                <Device><IPAddress><xsl:value-of select="$dstIp"/></IPAddress></Device>
                <TransportProtocol><xsl:value-of select="$proto"/></TransportProtocol>
                <xsl:if test="$dstPort != ''"><Port><xsl:value-of select="xs:integer($dstPort)"/></Port></xsl:if>
              </Destination>
              <xsl:copy-of select="$outcome"/>
            </xsl:element>
          </Network>
        </EventDetail>
      </Event>
    </xsl:if>
  </xsl:template>
</xsl:stylesheet>
