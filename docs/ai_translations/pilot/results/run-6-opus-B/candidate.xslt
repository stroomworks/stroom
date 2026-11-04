<?xml version="1.0" encoding="UTF-8"?>
<!--
  Firewall log (Data Splitter records:2) to event-logging v4.1.0.

  One Event per valid record. Records that are not firewall events, or that
  cannot be expressed against the schema, produce no Event; all except the
  header comment line are reported with xsl:message.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:r="records:2"
                xmlns:fw="urn:local:firewall"
                xmlns="event-logging:3"
                exclude-result-prefixes="xs r fw">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- IPv4 and IPv6 patterns copied from evt:IPAddressSimpleType (anchored here). -->
  <xsl:variable name="v4" select="'(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}'"/>
  <xsl:variable name="ipPatterns" as="xs:string*" select="(
      $v4,
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
      concat('::', $v4),
      concat('([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,4}:', $v4),
      concat('([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,3}:', $v4),
      concat('([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,2}:', $v4),
      concat('([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,1}:', $v4),
      concat('([0-9a-f]{1,4}:){1,5}:', $v4),
      concat(':(:[0-9a-f]{1,4}){1,5}:', $v4),
      concat('([0-9a-f]{1,4}:){6}', $v4))"/>

  <xsl:function name="fw:is-ip" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="some $p in $ipPatterns satisfies matches($value, concat('^(', $p, ')$'))"/>
  </xsl:function>

  <!-- A port is either absent ('-') or an integer 0..65535. -->
  <xsl:function name="fw:is-port-ok" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="$value = '-'
        or ($value castable as xs:integer
            and xs:integer($value) ge 0 and xs:integer($value) le 65535)"/>
  </xsl:function>

  <xsl:function name="fw:protocol" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="if ($value = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $value else 'Other'"/>
  </xsl:function>

  <xsl:template match="/r:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="r:record"/>
    </Events>
  </xsl:template>

  <!-- Header comment line: not an event, dropped silently. -->
  <xsl:template match="r:record[r:data/@name = 'comment']" priority="2"/>

  <!-- Unparsed (garbled) line: no event, reported. -->
  <xsl:template match="r:record[r:data/@name = 'raw']" priority="2">
    <xsl:message>Dropped unparsed record <xsl:value-of select="count(preceding-sibling::r:record) + 1"/>: <xsl:value-of select="r:data[@name = 'raw']/@value"/></xsl:message>
  </xsl:template>

  <xsl:template match="r:record">
    <xsl:variable name="n" select="count(preceding-sibling::r:record) + 1"/>
    <xsl:variable name="time" select="string(r:data[@name = 'time']/@value)"/>
    <xsl:variable name="host" select="string(r:data[@name = 'host']/@value)"/>
    <xsl:variable name="action" select="string(r:data[@name = 'action']/@value)"/>
    <xsl:variable name="src" select="string(r:data[@name = 'src']/@value)"/>
    <xsl:variable name="dst" select="string(r:data[@name = 'dst']/@value)"/>
    <xsl:variable name="proto" select="string(r:data[@name = 'proto']/@value)"/>
    <xsl:variable name="sport" select="string(r:data[@name = 'sport']/@value)"/>
    <xsl:variable name="dport" select="string(r:data[@name = 'dport']/@value)"/>

    <xsl:variable name="problem" as="xs:string?" select="
        if (not($time castable as xs:dateTime)) then concat('invalid time ''', $time, '''')
        else if ($host = '') then 'missing host'
        else if (not($action = ('DROP', 'ACCEPT'))) then concat('unrecognised action ''', $action, '''')
        else if (not(fw:is-ip($src))) then concat('invalid source IP ''', $src, '''')
        else if ($dst != '-' and not(fw:is-ip($dst))) then concat('invalid destination IP ''', $dst, '''')
        else if (not(fw:is-port-ok($sport))) then concat('invalid source port ''', $sport, '''')
        else if (not(fw:is-port-ok($dport))) then concat('invalid destination port ''', $dport, '''')
        else ()"/>

    <xsl:choose>
      <xsl:when test="exists($problem)">
        <xsl:message>Dropped record <xsl:value-of select="$n"/>: <xsl:value-of select="$problem"/></xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="utc" select="adjust-dateTime-to-timezone(xs:dateTime($time), xs:dayTimeDuration('PT0S'))"/>
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="format-dateTime($utc, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
            </TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>Firewall</Name>
              <Environment>Production</Environment>
            </System>
            <Generator>Firewall</Generator>
            <Device>
              <HostName><xsl:value-of select="$host"/></HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId><xsl:value-of select="concat('FW-', $action)"/></TypeId>
            <Network>
              <xsl:element name="{if ($action = 'DROP') then 'Deny' else 'Permit'}">
                <Source>
                  <Device>
                    <IPAddress><xsl:value-of select="$src"/></IPAddress>
                  </Device>
                  <TransportProtocol><xsl:value-of select="fw:protocol($proto)"/></TransportProtocol>
                  <xsl:if test="$sport != '-'">
                    <Port><xsl:value-of select="xs:integer($sport)"/></Port>
                  </xsl:if>
                </Source>
                <xsl:if test="$dst != '-'">
                  <Destination>
                    <Device>
                      <IPAddress><xsl:value-of select="$dst"/></IPAddress>
                    </Device>
                    <TransportProtocol><xsl:value-of select="fw:protocol($proto)"/></TransportProtocol>
                    <xsl:if test="$dport != '-'">
                      <Port><xsl:value-of select="xs:integer($dport)"/></Port>
                    </xsl:if>
                  </Destination>
                </xsl:if>
              </xsl:element>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

</xsl:stylesheet>
