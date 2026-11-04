<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translates fw01 firewall log records (Data Splitter output, records:2)
  into event-logging v4.1.0 (event-logging:3) Network Permit/Deny events.

  Record handling:
    * Records lacking a parseable time, a host or a recognised action are
      skipped (comment lines, garbled lines).
    * Fields whose value is "-" (or empty) are treated as absent.
    * An address that is not a valid IPv4/IPv6 address is not put in
      IPAddress; its raw value is kept as Device/Data instead.
    * A port that is not an integer in 0..65535 is omitted.
    * Protocols outside the schema enumeration become "Other", with the
      original protocol kept as Data.
    * Times with any offset are normalised to UTC, yyyy-MM-ddThh:mm:ss.sssZ.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:fw="urn:local:fw-functions"
                xmlns="event-logging:3"
                xpath-default-namespace="records:2"
                exclude-result-prefixes="xs rec fw">

  <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="yes"/>

  <!-- Fixed event source details (not present in the log itself). -->
  <xsl:param name="systemName" select="'Firewall'"/>
  <xsl:param name="environment" select="'Production'"/>
  <xsl:param name="generator" select="'fw01 firewall log'"/>

  <xsl:variable name="ipv4Regex"
                select="'^(25[0-5]|2[0-4][0-9]|[0-1]?[0-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9]?[0-9])){3}$'"/>

  <xsl:template match="/records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="record"/>
    </Events>
  </xsl:template>

  <!-- A valid record: has a parseable time, a host and a known action. -->
  <xsl:template match="record[fw:value(., 'time') castable as xs:dateTime]
                             [fw:value(., 'host') != '']
                             [fw:actionElement(fw:value(., 'action')) != '']"
                priority="2">
    <xsl:variable name="action" select="upper-case(fw:value(., 'action'))"/>
    <xsl:variable name="host" select="fw:value(., 'host')"/>
    <xsl:variable name="proto" select="upper-case(fw:value(., 'proto'))"/>

    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="fw:utcTime(fw:value(., 'time'))"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name><xsl:value-of select="$systemName"/></Name>
          <Environment><xsl:value-of select="$environment"/></Environment>
        </System>
        <Generator><xsl:value-of select="$generator"/></Generator>
        <Device>
          <HostName><xsl:value-of select="$host"/></HostName>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="concat('Firewall-', $action)"/></TypeId>
        <Description>
          <xsl:value-of select="concat('Firewall ', $host, ' ', $action, ' ',
                                       if ($proto != '') then concat($proto, ' ') else '',
                                       'traffic')"/>
        </Description>
        <Network>
          <xsl:element name="{fw:actionElement($action)}">
            <Source>
              <xsl:call-template name="location">
                <xsl:with-param name="ip" select="fw:value(., 'src')"/>
                <xsl:with-param name="port" select="fw:value(., 'sport')"/>
                <xsl:with-param name="proto" select="$proto"/>
              </xsl:call-template>
            </Source>
            <xsl:if test="fw:value(., 'dst') != '' or fw:value(., 'dport') != ''">
              <Destination>
                <xsl:call-template name="location">
                  <xsl:with-param name="ip" select="fw:value(., 'dst')"/>
                  <xsl:with-param name="port" select="fw:value(., 'dport')"/>
                  <xsl:with-param name="proto" select="$proto"/>
                </xsl:call-template>
              </Destination>
            </xsl:if>
          </xsl:element>
        </Network>
      </EventDetail>
    </Event>
  </xsl:template>

  <!-- Anything else (comments, garbled lines, unknown actions) is dropped. -->
  <xsl:template match="record" priority="1"/>

  <xsl:template name="location">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:param name="proto" as="xs:string"/>

    <xsl:variable name="normalisedIp" select="fw:normaliseIp($ip)"/>
    <xsl:if test="$ip != ''">
      <Device>
        <xsl:choose>
          <xsl:when test="$normalisedIp != ''">
            <IPAddress><xsl:value-of select="$normalisedIp"/></IPAddress>
          </xsl:when>
          <xsl:otherwise>
            <Data Name="InvalidIPAddress" Value="{$ip}"/>
          </xsl:otherwise>
        </xsl:choose>
      </Device>
    </xsl:if>
    <xsl:if test="$proto != ''">
      <TransportProtocol>
        <xsl:value-of select="if ($proto = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $proto else 'Other'"/>
      </TransportProtocol>
    </xsl:if>
    <xsl:if test="$port castable as xs:integer
                  and xs:integer($port) ge 0 and xs:integer($port) le 65535">
      <Port><xsl:value-of select="xs:integer($port)"/></Port>
    </xsl:if>
    <xsl:if test="$proto != '' and not($proto = ('TCP', 'UDP', 'ICMP', 'IGMP'))">
      <Data Name="Protocol" Value="{$proto}"/>
    </xsl:if>
  </xsl:template>

  <!-- Value of the named data field, trimmed; '-' and missing become ''. -->
  <xsl:function name="fw:value" as="xs:string">
    <xsl:param name="record" as="element()"/>
    <xsl:param name="name" as="xs:string"/>
    <xsl:variable name="v" select="normalize-space(($record/data[@name = $name]/@value)[1])"/>
    <xsl:sequence select="if ($v = '-') then '' else $v"/>
  </xsl:function>

  <!-- Maps a firewall action to its event-logging Network element name. -->
  <xsl:function name="fw:actionElement" as="xs:string">
    <xsl:param name="action" as="xs:string"/>
    <xsl:variable name="a" select="upper-case($action)"/>
    <xsl:sequence select="if ($a = ('ACCEPT', 'ALLOW', 'PERMIT', 'PASS')) then 'Permit'
                          else if ($a = ('DROP', 'DENY', 'REJECT', 'BLOCK')) then 'Deny'
                          else ''"/>
  </xsl:function>

  <!-- Converts any xs:dateTime to UTC in yyyy-MM-ddThh:mm:ss.sssZ form.
       A time without a zone is assumed to be UTC. -->
  <xsl:function name="fw:utcTime" as="xs:string">
    <xsl:param name="time" as="xs:string"/>
    <xsl:variable name="dt" select="xs:dateTime($time)"/>
    <xsl:variable name="utc"
                  select="if (empty(timezone-from-dateTime($dt)))
                          then $dt
                          else adjust-dateTime-to-timezone($dt, xs:dayTimeDuration('PT0S'))"/>
    <xsl:sequence select="format-dateTime($utc, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
  </xsl:function>

  <!-- Returns the address in schema form (IPv6 lower-cased), or '' if invalid. -->
  <xsl:function name="fw:normaliseIp" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:variable name="lower" select="lower-case($ip)"/>
    <xsl:sequence select="if (matches($ip, $ipv4Regex)) then $ip
                          else if (fw:isIpv6($lower)) then $lower
                          else ''"/>
  </xsl:function>

  <!-- IPv6 check: at most one '::', 1-4 hex digit groups, correct group count. -->
  <xsl:function name="fw:isIpv6" as="xs:boolean">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:variable name="halves" select="tokenize($ip, '::')"/>
    <xsl:variable name="groups"
                  select="for $h in $halves return (if ($h = '') then () else tokenize($h, ':'))"/>
    <xsl:sequence select="contains($ip, ':')
                          and count($halves) le 2
                          and (every $g in $groups satisfies matches($g, '^[0-9a-f]{1,4}$'))
                          and (if (contains($ip, '::')) then count($groups) le 7
                               else count($groups) = 8)"/>
  </xsl:function>

</xsl:stylesheet>
