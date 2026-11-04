<?xml version="1.1" encoding="UTF-8"?>
<!--
  Translates headerless firewall CSV records (Data Splitter records:2 output)
  into event-logging v4.1.0 Network Permit/Deny events.

  CSV column order (no header):
    1 date/time  (dd/MM/yyyy HH:mm:ss, assumed UTC)
    2 firewall host name
    3 action     (ALLOW | BLOCK | RESET)
    4 source IP
    5 destination IP
    6 source port      (may be empty, e.g. ICMP)
    7 destination port (may be empty, e.g. ICMP)
    8 transport protocol (tcp | udp | icmp | igmp | other)

  Records that fail validation are dropped and reported with xsl:message.
-->
<xsl:stylesheet
    xmlns="event-logging:3"
    xmlns:evt="event-logging:3"
    xmlns:rec="records:2"
    xmlns:local="local"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    exclude-result-prefixes="evt rec local xs"
    version="2.0">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- Values not present in the data; override as stylesheet parameters if needed. -->
  <xsl:param name="systemName" select="'Firewall'"/>
  <xsl:param name="systemEnvironment" select="'Production'"/>
  <xsl:param name="generator" select="'Firewall CSV Log'"/>

  <xsl:variable name="expectedFieldCount" select="8"/>

  <!-- Union of the IPAddressSimpleType patterns from event-logging-v4_1_0.xsd. -->
  <xsl:variable name="ipRegex">^(((25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(::)|(([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,6})|(([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,5})|(([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,4})|(([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,3})|(([0-9a-f]{1,4}:){1,5}(:[0-9a-f]{1,4}){1,2})|(([0-9a-f]{1,4}:){1,6}(:[0-9a-f]{1,4}){1,1})|(([0-9a-f]{1,4}:){1,7}:)|(:(:[0-9a-f]{1,4}){1,7})|(([0-9a-f]{1,4}:){7}[0-9a-f]{1,4})|(::(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,4}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,3}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,2}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,1}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(([0-9a-f]{1,4}:){1,5}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(:(:[0-9a-f]{1,4}){1,5}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3})|(([0-9a-f]{1,4}:){6}(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}))$</xsl:variable>

  <xsl:template match="/rec:records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd" Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="fields" select="rec:data/string(@value)"/>
    <xsl:variable name="errors" select="local:validate($fields)"/>
    <xsl:choose>
      <xsl:when test="exists($errors)">
        <xsl:message terminate="no">
          <xsl:value-of select="concat('Skipping record ', count(preceding-sibling::rec:record) + 1, ': ', string-join($errors, '; '))"/>
        </xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="event">
          <xsl:with-param name="fields" select="$fields"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="event">
    <xsl:param name="fields" as="xs:string*"/>
    <xsl:variable name="action" select="upper-case(normalize-space($fields[3]))"/>
    <xsl:variable name="srcIp" select="lower-case(normalize-space($fields[4]))"/>
    <xsl:variable name="dstIp" select="lower-case(normalize-space($fields[5]))"/>
    <xsl:variable name="srcPort" select="normalize-space($fields[6])"/>
    <xsl:variable name="dstPort" select="normalize-space($fields[7])"/>
    <xsl:variable name="rawProtocol" select="normalize-space($fields[8])"/>
    <xsl:variable name="protocol" select="local:protocol($rawProtocol)"/>
    <xsl:variable name="permitted" select="$action = 'ALLOW'"/>

    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="local:to-iso-date(normalize-space($fields[1]))"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name><xsl:value-of select="$systemName"/></Name>
          <Environment><xsl:value-of select="$systemEnvironment"/></Environment>
        </System>
        <Generator><xsl:value-of select="$generator"/></Generator>
        <Device>
          <HostName><xsl:value-of select="normalize-space($fields[2])"/></HostName>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="concat('Firewall-', $action)"/></TypeId>
        <Description>
          <xsl:value-of select="concat(
              'Firewall ',
              if ($permitted) then 'permitted' else if ($action = 'RESET') then 'reset' else 'blocked',
              ' ', $rawProtocol, ' traffic from ',
              local:endpoint($srcIp, $srcPort), ' to ', local:endpoint($dstIp, $dstPort))"/>
        </Description>
        <Network>
          <xsl:element name="{if ($permitted) then 'Permit' else 'Deny'}">
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
            <Data Name="FirewallAction" Value="{$action}"/>
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
    <xsl:param name="protocol" as="xs:string"/>
    <Device>
      <IPAddress><xsl:value-of select="$ip"/></IPAddress>
    </Device>
    <TransportProtocol><xsl:value-of select="$protocol"/></TransportProtocol>
    <xsl:if test="$port != ''">
      <Port><xsl:value-of select="xs:integer($port)"/></Port>
    </xsl:if>
  </xsl:template>

  <!-- Returns a list of validation error messages; empty if the record is valid. -->
  <xsl:function name="local:validate" as="xs:string*">
    <xsl:param name="fields" as="xs:string*"/>
    <xsl:choose>
      <xsl:when test="count($fields) != $expectedFieldCount">
        <xsl:sequence select="concat('expected ', $expectedFieldCount, ' fields but found ', count($fields))"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:if test="empty(local:to-iso-date(normalize-space($fields[1])))">
          <xsl:sequence select="concat('invalid date/time [', $fields[1], ']')"/>
        </xsl:if>
        <xsl:if test="normalize-space($fields[2]) = ''">
          <xsl:sequence select="'missing firewall host name'"/>
        </xsl:if>
        <xsl:if test="not(upper-case(normalize-space($fields[3])) = ('ALLOW', 'BLOCK', 'RESET'))">
          <xsl:sequence select="concat('unknown action [', $fields[3], ']')"/>
        </xsl:if>
        <xsl:if test="not(matches(lower-case(normalize-space($fields[4])), $ipRegex))">
          <xsl:sequence select="concat('invalid source IP [', $fields[4], ']')"/>
        </xsl:if>
        <xsl:if test="not(matches(lower-case(normalize-space($fields[5])), $ipRegex))">
          <xsl:sequence select="concat('invalid destination IP [', $fields[5], ']')"/>
        </xsl:if>
        <xsl:if test="not(local:is-valid-port(normalize-space($fields[6])))">
          <xsl:sequence select="concat('invalid source port [', $fields[6], ']')"/>
        </xsl:if>
        <xsl:if test="not(local:is-valid-port(normalize-space($fields[7])))">
          <xsl:sequence select="concat('invalid destination port [', $fields[7], ']')"/>
        </xsl:if>
        <xsl:if test="normalize-space($fields[8]) = ''">
          <xsl:sequence select="'missing protocol'"/>
        </xsl:if>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <!-- An empty port is allowed (e.g. ICMP); otherwise 0-65535. -->
  <xsl:function name="local:is-valid-port" as="xs:boolean">
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="$port = ''
        or (matches($port, '^\d{1,5}$') and xs:integer($port) le 65535)"/>
  </xsl:function>

  <!-- Converts 'dd/MM/yyyy HH:mm:ss' (assumed UTC) to 'yyyy-MM-ddTHH:mm:ss.000Z'; empty if invalid. -->
  <xsl:function name="local:to-iso-date" as="xs:string?">
    <xsl:param name="value" as="xs:string"/>
    <xsl:if test="matches($value, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')">
      <xsl:variable name="iso" select="replace($value,
          '^(\d{2})/(\d{2})/(\d{4}) (\d{2}:\d{2}:\d{2})$', '$3-$2-$1T$4.000Z')"/>
      <xsl:if test="$iso castable as xs:dateTime">
        <xsl:sequence select="$iso"/>
      </xsl:if>
    </xsl:if>
  </xsl:function>

  <xsl:function name="local:protocol" as="xs:string">
    <xsl:param name="raw" as="xs:string"/>
    <xsl:variable name="upper" select="upper-case($raw)"/>
    <xsl:sequence select="if ($upper = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $upper else 'Other'"/>
  </xsl:function>

  <xsl:function name="local:endpoint" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="if ($port = '') then $ip
        else if (contains($ip, ':')) then concat('[', $ip, ']:', $port)
        else concat($ip, ':', $port)"/>
  </xsl:function>

</xsl:stylesheet>
