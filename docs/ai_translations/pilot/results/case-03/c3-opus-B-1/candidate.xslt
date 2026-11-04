<?xml version="1.0" encoding="UTF-8"?>
<!--
  Firewall CSV (no header) -> event-logging v4.1.0.
  Columns: 1 time, 2 gateway host, 3 action, 4 src IP, 5 dest IP,
           6 src port, 7 dest port, 8 protocol.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:local="urn:local"
                xmlns="event-logging:3"
                exclude-result-prefixes="xs rec local">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- IPAddressSimpleType patterns, copied verbatim from event-logging-v4_1_0.xsd -->
  <xsl:variable name="ipPatterns" as="xs:string+" select="(
            '(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '::',
            '([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,6}',
            '([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,5}',
            '([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,4}',
            '([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,3}',
            '([0-9a-f]{1,4}:){1,5}(:[0-9a-f]{1,4}){1,2}',
            '([0-9a-f]{1,4}:){1,6}(:[0-9a-f]{1,4}){1,1}',
            '([0-9a-f]{1,4}:){1,7}:',
            ':(:[0-9a-f]{1,4}){1,7}',
            '([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}',
            '::(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,4}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,3}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,2}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,1}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '([0-9a-f]{1,4}:){1,5}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            ':(:[0-9a-f]{1,4}){1,5}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
            '([0-9a-f]{1,4}:){6}(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}')"/>

  <xsl:function name="local:isValidIp" as="xs:boolean">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:sequence select="some $p in $ipPatterns satisfies matches($ip, concat('^(', $p, ')$'))"/>
  </xsl:function>

  <xsl:template match="/rec:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="time" select="normalize-space(rec:data[1]/@value)"/>
    <xsl:variable name="host" select="normalize-space(rec:data[2]/@value)"/>
    <xsl:variable name="action" select="upper-case(normalize-space(rec:data[3]/@value))"/>
    <xsl:variable name="srcIp" select="normalize-space(rec:data[4]/@value)"/>
    <xsl:variable name="destIp" select="normalize-space(rec:data[5]/@value)"/>
    <xsl:variable name="srcPort" select="normalize-space(rec:data[6]/@value)"/>
    <xsl:variable name="destPort" select="normalize-space(rec:data[7]/@value)"/>
    <xsl:variable name="protocol" select="upper-case(normalize-space(rec:data[8]/@value))"/>
    <xsl:variable name="rowNum" select="count(preceding-sibling::rec:record) + 1"/>

    <xsl:variable name="actionElement" as="xs:string?" select="
        if ($action = 'DENY') then 'Deny'
        else if ($action = 'PERMIT') then 'Permit'
        else if ($action = 'TEARDOWN') then 'Close'
        else ()"/>

    <xsl:choose>
      <xsl:when test="not(local:isValidIp($srcIp)) or not(local:isValidIp($destIp))">
        <xsl:message>
          <xsl:value-of select="concat('Dropping record ', $rowNum, ' (', $time,
              '): invalid IP address (source=''', $srcIp, ''', destination=''', $destIp, ''')')"/>
        </xsl:message>
      </xsl:when>
      <xsl:when test="empty($actionElement)">
        <xsl:message>
          <xsl:value-of select="concat('Dropping record ', $rowNum, ' (', $time,
              '): unrecognised action ''', $action, '''')"/>
        </xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <Event>
          <EventTime>
            <!-- Schema requires yyyy-MM-ddTHH:mm:ss.SSSZ (UTC, milliseconds) -->
            <TimeCreated>
              <xsl:value-of select="
                  if ($time castable as xs:dateTime)
                  then format-dateTime(
                         adjust-dateTime-to-timezone(xs:dateTime($time), xs:dayTimeDuration('PT0S')),
                         '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')
                  else $time"/>
            </TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>Firewall</Name>
              <Environment>Production</Environment>
            </System>
            <Generator>CSV firewall log</Generator>
            <Device>
              <HostName><xsl:value-of select="$host"/></HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId>FirewallTraffic</TypeId>
            <Network>
              <xsl:element name="{$actionElement}">
                <Source>
                  <xsl:call-template name="location">
                    <xsl:with-param name="ip" select="$srcIp"/>
                    <xsl:with-param name="port" select="$srcPort"/>
                    <xsl:with-param name="protocol" select="$protocol"/>
                  </xsl:call-template>
                </Source>
                <Destination>
                  <xsl:call-template name="location">
                    <xsl:with-param name="ip" select="$destIp"/>
                    <xsl:with-param name="port" select="$destPort"/>
                    <xsl:with-param name="protocol" select="$protocol"/>
                  </xsl:call-template>
                </Destination>
              </xsl:element>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="location">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:param name="protocol" as="xs:string"/>
    <Device>
      <IPAddress><xsl:value-of select="$ip"/></IPAddress>
    </Device>
    <xsl:if test="$protocol != ''">
      <TransportProtocol>
        <xsl:value-of select="if ($protocol = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $protocol else 'Other'"/>
      </TransportProtocol>
    </xsl:if>
    <xsl:if test="$port castable as xs:int and xs:int($port) ge 0 and xs:int($port) le 65535">
      <Port><xsl:value-of select="xs:int($port)"/></Port>
    </xsl:if>
  </xsl:template>

</xsl:stylesheet>
