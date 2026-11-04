<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns="event-logging:3"
                exclude-result-prefixes="rec xs">

  <!-- Firewall CSV (no header). Columns: 1 time (dd/MM/yyyy HH:mm:ss, UTC), 2 firewall host,
       3 action (BLOCK|ALLOW|RESET), 4 source IP, 5 destination IP, 6 source port,
       7 destination port, 8 protocol. -->

  <xsl:template match="/rec:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="position" select="count(preceding-sibling::rec:record) + 1"/>
    <xsl:variable name="d" select="for $v in rec:data/@value return normalize-space($v)" as="xs:string*"/>
    <xsl:variable name="time" select="$d[1]"/>
    <xsl:variable name="host" select="$d[2]"/>
    <xsl:variable name="action" select="upper-case($d[3])"/>
    <xsl:variable name="srcIp" select="$d[4]"/>
    <xsl:variable name="dstIp" select="$d[5]"/>
    <xsl:variable name="srcPort" select="$d[6]"/>
    <xsl:variable name="dstPort" select="$d[7]"/>
    <xsl:variable name="protocol" select="upper-case($d[8])"/>

    <xsl:variable name="ipv4" select="'^((25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])$'"/>
    <xsl:variable name="ipv6" select="'^[0-9a-fA-F:]*:[0-9a-fA-F:]*$'"/>

    <xsl:choose>
      <xsl:when test="count($d) lt 8">
        <xsl:message>WARNING: record <xsl:value-of select="$position"/> dropped: expected 8 fields but found <xsl:value-of select="count($d)"/></xsl:message>
      </xsl:when>
      <xsl:when test="not(matches($time, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$'))">
        <xsl:message>WARNING: record <xsl:value-of select="$position"/> dropped: invalid time '<xsl:value-of select="$time"/>'</xsl:message>
      </xsl:when>
      <xsl:when test="not($action = ('BLOCK', 'ALLOW', 'RESET'))">
        <xsl:message>WARNING: record <xsl:value-of select="$position"/> dropped: unknown action '<xsl:value-of select="$d[3]"/>'</xsl:message>
      </xsl:when>
      <xsl:when test="not(matches($srcIp, $ipv4) or matches($srcIp, $ipv6))">
        <xsl:message>WARNING: record <xsl:value-of select="$position"/> dropped: invalid source IP '<xsl:value-of select="$srcIp"/>'</xsl:message>
      </xsl:when>
      <xsl:when test="not(matches($dstIp, $ipv4) or matches($dstIp, $ipv6))">
        <xsl:message>WARNING: record <xsl:value-of select="$position"/> dropped: invalid destination IP '<xsl:value-of select="$dstIp"/>'</xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="replace($time, '^(\d{2})/(\d{2})/(\d{4}) (\d{2}:\d{2}:\d{2})$', '$3-$2-$1T$4.000Z')"/>
            </TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>Firewall</Name>
              <Environment>Unknown</Environment>
            </System>
            <Generator>Firewall</Generator>
            <Device>
              <HostName><xsl:value-of select="$host"/></HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId>
              <xsl:text>Firewall </xsl:text>
              <xsl:value-of select="lower-case($action)"/>
            </TypeId>
            <Network>
              <xsl:variable name="body">
                <Source>
                  <Device><IPAddress><xsl:value-of select="lower-case($srcIp)"/></IPAddress></Device>
                  <xsl:call-template name="protocol"><xsl:with-param name="p" select="$protocol"/></xsl:call-template>
                  <xsl:call-template name="port"><xsl:with-param name="p" select="$srcPort"/></xsl:call-template>
                </Source>
                <Destination>
                  <Device><IPAddress><xsl:value-of select="lower-case($dstIp)"/></IPAddress></Device>
                  <xsl:call-template name="protocol"><xsl:with-param name="p" select="$protocol"/></xsl:call-template>
                  <xsl:call-template name="port"><xsl:with-param name="p" select="$dstPort"/></xsl:call-template>
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

  <xsl:template name="protocol">
    <xsl:param name="p"/>
    <xsl:if test="$p != ''">
      <TransportProtocol>
        <xsl:value-of select="if ($p = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $p else 'Other'"/>
      </TransportProtocol>
    </xsl:if>
  </xsl:template>

  <!-- Empty or out-of-range ports are omitted. -->
  <xsl:template name="port">
    <xsl:param name="p"/>
    <xsl:if test="matches($p, '^\d{1,5}$') and xs:integer($p) le 65535">
      <Port><xsl:value-of select="xs:integer($p)"/></Port>
    </xsl:if>
  </xsl:template>

</xsl:stylesheet>
