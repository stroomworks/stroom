<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translation of a header-less firewall CSV (parsed by Stroom's Data Splitter into records:2)
  into event-logging v4.1.0.

  Column order (inferred from the data):
    1 timestamp  dd/MM/yyyy HH:mm:ss (assumed UTC)
    2 firewall host name
    3 action     BLOCK | ALLOW | RESET
    4 source IP
    5 destination IP
    6 source port       (may be empty, e.g. ICMP)
    7 destination port  (may be empty, e.g. ICMP)
    8 transport protocol (tcp | udp | icmp | ...)

  Records that are not valid (wrong number of columns, bad timestamp, unknown action,
  bad IP address, bad port) produce no Event; a warning is emitted with xsl:message instead.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:r="records:2"
                xmlns:l="local-functions"
                xmlns="event-logging:3"
                exclude-result-prefixes="xsl xs r l">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- Values not present in the data. Override as stylesheet parameters if known. -->
  <xsl:param name="systemName" select="'Firewall'"/>
  <xsl:param name="systemEnvironment" select="'Unknown'"/>
  <xsl:param name="generator" select="'Firewall CSV log'"/>

  <!-- IPv4 / IPv6 patterns copied from evt:IPAddressSimpleType, so that a value accepted here
       is also accepted by the schema. -->
  <xsl:variable name="ipv4" select="'(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}'"/>
  <xsl:variable name="ipPatterns" as="xs:string*" select="(
      $ipv4,
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
      concat('::', $ipv4),
      concat('([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,4}:', $ipv4),
      concat('([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,3}:', $ipv4),
      concat('([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,2}:', $ipv4),
      concat('([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,1}:', $ipv4),
      concat('([0-9a-f]{1,4}:){1,5}:', $ipv4),
      concat(':(:[0-9a-f]{1,4}){1,5}:', $ipv4),
      concat('([0-9a-f]{1,4}:){6}', $ipv4))"/>

  <xsl:template match="/r:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="r:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="r:record">
    <xsl:variable name="values" as="xs:string*" select="r:data/normalize-space(@value)"/>
    <xsl:variable name="problem" as="xs:string?" select="l:validate($values)"/>
    <xsl:choose>
      <xsl:when test="exists($problem)">
        <xsl:message terminate="no">
          <xsl:value-of select="concat('Record ', count(preceding-sibling::r:record) + 1,
                                       ' skipped: ', $problem)"/>
        </xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="event">
          <xsl:with-param name="values" select="$values"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="event">
    <xsl:param name="values" as="xs:string*"/>
    <xsl:variable name="host" select="$values[2]"/>
    <xsl:variable name="action" select="upper-case($values[3])"/>
    <xsl:variable name="srcIp" select="lower-case($values[4])"/>
    <xsl:variable name="dstIp" select="lower-case($values[5])"/>
    <xsl:variable name="srcPort" select="$values[6]"/>
    <xsl:variable name="dstPort" select="$values[7]"/>
    <xsl:variable name="rawProtocol" select="$values[8]"/>
    <xsl:variable name="protocol" select="l:protocol($rawProtocol)"/>
    <xsl:variable name="networkAction" select="if ($action = 'ALLOW') then 'Permit' else 'Deny'"/>

    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="l:timestamp($values[1])"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name><xsl:value-of select="$systemName"/></Name>
          <Environment><xsl:value-of select="$systemEnvironment"/></Environment>
        </System>
        <Generator><xsl:value-of select="$generator"/></Generator>
        <Device>
          <HostName><xsl:value-of select="$host"/></HostName>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="concat('Firewall-', $action)"/></TypeId>
        <Description>
          <xsl:value-of select="concat(
              if ($action = 'ALLOW') then 'Firewall permitted '
              else if ($action = 'RESET') then 'Firewall denied (reset) '
              else 'Firewall blocked ',
              upper-case($rawProtocol), ' traffic from ',
              l:endpoint($srcIp, $srcPort), ' to ', l:endpoint($dstIp, $dstPort))"/>
        </Description>
        <Network>
          <xsl:element name="{$networkAction}">
            <Source>
              <xsl:call-template name="location">
                <xsl:with-param name="ip" select="$srcIp"/>
                <xsl:with-param name="port" select="$srcPort"/>
                <xsl:with-param name="protocol" select="$protocol"/>
              </xsl:call-template>
            </Source>
            <Destination>
              <xsl:call-template name="location">
                <xsl:with-param name="ip" select="$dstIp"/>
                <xsl:with-param name="port" select="$dstPort"/>
                <xsl:with-param name="protocol" select="$protocol"/>
              </xsl:call-template>
            </Destination>
            <xsl:if test="$networkAction = 'Deny'">
              <Outcome>
                <Permitted>false</Permitted>
              </Outcome>
            </xsl:if>
            <Data Name="Action" Value="{$values[3]}"/>
            <xsl:if test="$protocol = 'Other'">
              <Data Name="Protocol" Value="{$rawProtocol}"/>
            </xsl:if>
          </xsl:element>
        </Network>
      </EventDetail>
    </Event>
  </xsl:template>

  <xsl:template name="location">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:param name="protocol" as="xs:string?"/>
    <Device>
      <IPAddress><xsl:value-of select="$ip"/></IPAddress>
    </Device>
    <xsl:if test="exists($protocol)">
      <TransportProtocol><xsl:value-of select="$protocol"/></TransportProtocol>
    </xsl:if>
    <xsl:if test="$port != ''">
      <Port><xsl:value-of select="xs:integer($port)"/></Port>
    </xsl:if>
  </xsl:template>

  <!-- Returns a description of why the record is invalid, or the empty sequence if it is valid. -->
  <xsl:function name="l:validate" as="xs:string?">
    <xsl:param name="v" as="xs:string*"/>
    <xsl:sequence select="
        if (count($v) != 8) then concat('expected 8 columns but found ', count($v))
        else if (empty(l:timestamp($v[1]))) then concat('invalid timestamp &quot;', $v[1], '&quot;')
        else if ($v[2] = '') then 'missing firewall host name'
        else if (not(upper-case($v[3]) = ('ALLOW', 'BLOCK', 'RESET'))) then concat('unknown action &quot;', $v[3], '&quot;')
        else if (not(l:isIp($v[4]))) then concat('invalid source IP address &quot;', $v[4], '&quot;')
        else if (not(l:isIp($v[5]))) then concat('invalid destination IP address &quot;', $v[5], '&quot;')
        else if (not(l:isPortOrEmpty($v[6]))) then concat('invalid source port &quot;', $v[6], '&quot;')
        else if (not(l:isPortOrEmpty($v[7]))) then concat('invalid destination port &quot;', $v[7], '&quot;')
        else if ($v[8] = '') then 'missing protocol'
        else ()"/>
  </xsl:function>

  <!-- dd/MM/yyyy HH:mm:ss (assumed UTC) to yyyy-MM-ddTHH:mm:ss.000Z; empty if not a real date-time. -->
  <xsl:function name="l:timestamp" as="xs:string?">
    <xsl:param name="s" as="xs:string"/>
    <xsl:variable name="re" select="'^(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})$'"/>
    <xsl:variable name="iso" select="
        if (matches($s, $re))
        then replace($s, $re, '$3-$2-$1T$4:$5:$6.000Z')
        else ''"/>
    <xsl:sequence select="if ($iso castable as xs:dateTime) then $iso else ()"/>
  </xsl:function>

  <xsl:function name="l:isIp" as="xs:boolean">
    <xsl:param name="s" as="xs:string"/>
    <xsl:variable name="ip" select="lower-case($s)"/>
    <xsl:sequence select="some $p in $ipPatterns satisfies matches($ip, concat('^(', $p, ')$'))"/>
  </xsl:function>

  <xsl:function name="l:isPortOrEmpty" as="xs:boolean">
    <xsl:param name="s" as="xs:string"/>
    <xsl:sequence select="$s = '' or (matches($s, '^\d{1,5}$') and xs:integer($s) le 65535)"/>
  </xsl:function>

  <xsl:function name="l:protocol" as="xs:string">
    <xsl:param name="s" as="xs:string"/>
    <xsl:variable name="u" select="upper-case($s)"/>
    <xsl:sequence select="if ($u = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $u else 'Other'"/>
  </xsl:function>

  <xsl:function name="l:endpoint" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="
        if ($port = '') then $ip
        else if (contains($ip, ':')) then concat('[', $ip, ']:', $port)
        else concat($ip, ':', $port)"/>
  </xsl:function>

</xsl:stylesheet>
