<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2" xmlns:local="local"
                exclude-result-prefixes="rec xs local">

  <xsl:variable name="ipv4"
                select="'^(25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])){3}$'"/>

  <xsl:template match="/rec:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <!-- Columns: 1 time, 2 device, 3 action, 4 dest IP, 5 source IP, 6 dest port, 7 source port, 8 protocol -->
    <xsl:variable name="d" select="rec:data/@value"/>
    <xsl:variable name="time" select="normalize-space($d[1])"/>
    <xsl:variable name="device" select="normalize-space($d[2])"/>
    <xsl:variable name="action" select="upper-case(normalize-space($d[3]))"/>
    <xsl:variable name="destIp" select="normalize-space($d[4])"/>
    <xsl:variable name="srcIp" select="normalize-space($d[5])"/>
    <xsl:variable name="destPort" select="normalize-space($d[6])"/>
    <xsl:variable name="srcPort" select="normalize-space($d[7])"/>
    <xsl:variable name="proto" select="upper-case(normalize-space($d[8]))"/>

    <xsl:choose>
      <xsl:when test="count($d) != 8">
        <xsl:message>Dropping record <xsl:value-of select="position()"/>: expected 8 fields, found <xsl:value-of select="count($d)"/></xsl:message>
      </xsl:when>
      <xsl:when test="not($action = ('PERMIT', 'DENY', 'TEARDOWN'))">
        <xsl:message>Dropping record <xsl:value-of select="position()"/>: unknown action '<xsl:value-of select="$action"/>'</xsl:message>
      </xsl:when>
      <xsl:when test="not(local:valid-ip($srcIp)) or not(local:valid-ip($destIp))">
        <xsl:message>Dropping record <xsl:value-of select="position()"/>: invalid IP address (source '<xsl:value-of select="$srcIp"/>', destination '<xsl:value-of select="$destIp"/>')</xsl:message>
      </xsl:when>
      <xsl:when test="($srcPort != '' and not(local:valid-port($srcPort)))
                      or ($destPort != '' and not(local:valid-port($destPort)))">
        <xsl:message>Dropping record <xsl:value-of select="position()"/>: invalid port (source '<xsl:value-of select="$srcPort"/>', destination '<xsl:value-of select="$destPort"/>')</xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <Event>
          <EventTime>
            <TimeCreated><xsl:value-of select="replace($time, 'Z$', '.000Z')"/></TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>Gateway</Name>
              <Environment>Production</Environment>
            </System>
            <Generator>Gateway firewall log</Generator>
            <Device>
              <HostName><xsl:value-of select="$device"/></HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId><xsl:value-of select="$action"/></TypeId>
            <Network>
              <xsl:variable name="body">
                <Source>
                  <Device>
                    <IPAddress><xsl:value-of select="$srcIp"/></IPAddress>
                    <xsl:if test="$srcPort != ''">
                      <Port><xsl:value-of select="xs:integer($srcPort)"/></Port>
                    </xsl:if>
                  </Device>
                  <TransportProtocol><xsl:value-of select="local:protocol($proto)"/></TransportProtocol>
                </Source>
                <Destination>
                  <Device>
                    <IPAddress><xsl:value-of select="$destIp"/></IPAddress>
                    <xsl:if test="$destPort != ''">
                      <Port><xsl:value-of select="xs:integer($destPort)"/></Port>
                    </xsl:if>
                  </Device>
                  <TransportProtocol><xsl:value-of select="local:protocol($proto)"/></TransportProtocol>
                </Destination>
              </xsl:variable>
              <xsl:choose>
                <xsl:when test="$action = 'PERMIT'"><Permit><xsl:copy-of select="$body"/></Permit></xsl:when>
                <xsl:when test="$action = 'DENY'"><Deny><xsl:copy-of select="$body"/></Deny></xsl:when>
                <xsl:otherwise><Close><xsl:copy-of select="$body"/></Close></xsl:otherwise>
              </xsl:choose>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:function name="local:valid-ip" as="xs:boolean" xmlns:local="local">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:sequence select="if (contains($ip, ':'))
                          then matches($ip, '^[0-9a-fA-F:.]+$') and matches($ip, '::|^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$')
                          else matches($ip, $ipv4)"/>
  </xsl:function>

  <xsl:function name="local:valid-port" as="xs:boolean" xmlns:local="local">
    <xsl:param name="p" as="xs:string"/>
    <xsl:sequence select="matches($p, '^[0-9]{1,5}$') and xs:integer($p) le 65535"/>
  </xsl:function>

  <xsl:function name="local:protocol" as="xs:string" xmlns:local="local">
    <xsl:param name="p" as="xs:string"/>
    <xsl:sequence select="if ($p = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $p else 'Other'"/>
  </xsl:function>
</xsl:stylesheet>
