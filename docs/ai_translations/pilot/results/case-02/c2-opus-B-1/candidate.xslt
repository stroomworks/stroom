<?xml version="1.0" encoding="UTF-8"?>
<!-- Firewall CSV (no header) to event-logging v4.1.0.
     Columns: 1 time (dd/MM/yyyy HH:mm:ss, UTC), 2 firewall host, 3 action (BLOCK|ALLOW|RESET),
     4 source IP, 5 destination IP, 6 source port, 7 destination port, 8 protocol. -->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:local="local"
                xmlns="event-logging:3"
                exclude-result-prefixes="xs rec local">

  <xsl:output method="xml" indent="yes"/>

  <!-- Octet and IPv4 patterns, copied from IPAddressSimpleType in the schema. -->
  <xsl:variable name="o" select="'(25[0-5]|2[0-4]\d|[0-1]?\d?\d)'"/>
  <xsl:variable name="v4" select="concat($o, '(\.', $o, '){3}')"/>
  <xsl:variable name="h" select="'[0-9a-f]{1,4}'"/>
  <xsl:variable name="ipPatterns" as="xs:string*" select="(
      $v4,
      '::',
      concat('(', $h, ':){1,1}(:', $h, '){1,6}'),
      concat('(', $h, ':){1,2}(:', $h, '){1,5}'),
      concat('(', $h, ':){1,3}(:', $h, '){1,4}'),
      concat('(', $h, ':){1,4}(:', $h, '){1,3}'),
      concat('(', $h, ':){1,5}(:', $h, '){1,2}'),
      concat('(', $h, ':){1,6}(:', $h, '){1,1}'),
      concat('(', $h, ':){1,7}:'),
      concat(':(:', $h, '){1,7}'),
      concat('(', $h, ':){7}', $h),
      concat('::', $v4),
      concat('(', $h, ':){1,1}(:', $h, '){1,4}:', $v4),
      concat('(', $h, ':){1,2}(:', $h, '){1,3}:', $v4),
      concat('(', $h, ':){1,3}(:', $h, '){1,2}:', $v4),
      concat('(', $h, ':){1,4}(:', $h, '){1,1}:', $v4),
      concat('(', $h, ':){1,5}:', $v4),
      concat(':(:', $h, '){1,5}:', $v4),
      concat('(', $h, ':){6}', $v4))"/>

  <xsl:function name="local:validIp" as="xs:boolean">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:sequence select="some $p in $ipPatterns satisfies matches($ip, concat('^(', $p, ')$'))"/>
  </xsl:function>

  <!-- Empty, or an integer 0-65535. -->
  <xsl:function name="local:validPort" as="xs:boolean">
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="$port = '' or (matches($port, '^\d{1,5}$') and xs:integer($port) le 65535)"/>
  </xsl:function>

  <!-- dd/MM/yyyy HH:mm:ss to yyyy-MM-ddTHH:mm:ss.000Z, or empty if not a real date-time. -->
  <xsl:function name="local:isoTime" as="xs:string?">
    <xsl:param name="raw" as="xs:string"/>
    <xsl:if test="matches($raw, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')">
      <xsl:variable name="iso" select="replace($raw,
          '^(\d{2})/(\d{2})/(\d{4}) (\d{2}:\d{2}:\d{2})$', '$3-$2-$1T$4')"/>
      <xsl:if test="$iso castable as xs:dateTime">
        <xsl:sequence select="concat($iso, '.000Z')"/>
      </xsl:if>
    </xsl:if>
  </xsl:function>

  <xsl:function name="local:protocol" as="xs:string">
    <xsl:param name="raw" as="xs:string"/>
    <xsl:variable name="u" select="upper-case($raw)"/>
    <xsl:sequence select="if ($u = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $u else 'Other'"/>
  </xsl:function>

  <!-- Address with optional port; IPv6 addresses are bracketed when a port follows. -->
  <xsl:function name="local:endpoint" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="if ($port = '') then $ip
        else if (contains($ip, ':')) then concat('[', $ip, ']:', $port)
        else concat($ip, ':', $port)"/>
  </xsl:function>

  <xsl:template match="/rec:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="d" select="rec:data/string(@value)"/>
    <xsl:variable name="time" select="if (count($d) ge 1) then local:isoTime($d[1]) else ()"/>
    <xsl:variable name="host" select="normalize-space($d[2])"/>
    <xsl:variable name="action" select="upper-case(normalize-space($d[3]))"/>
    <xsl:variable name="srcIp" select="lower-case(normalize-space($d[4]))"/>
    <xsl:variable name="dstIp" select="lower-case(normalize-space($d[5]))"/>
    <xsl:variable name="srcPort" select="normalize-space($d[6])"/>
    <xsl:variable name="dstPort" select="normalize-space($d[7])"/>
    <xsl:variable name="proto" select="normalize-space($d[8])"/>

    <xsl:variable name="problem" as="xs:string?" select="
        if (count($d) ne 8) then concat('expected 8 columns but found ', count($d))
        else if (empty($time)) then concat('invalid time ''', $d[1], '''')
        else if ($host = '') then 'missing firewall host'
        else if (not($action = ('BLOCK', 'ALLOW', 'RESET'))) then concat('unknown action ''', $d[3], '''')
        else if (not(local:validIp($srcIp))) then concat('invalid source IP ''', $d[4], '''')
        else if (not(local:validIp($dstIp))) then concat('invalid destination IP ''', $d[5], '''')
        else if (not(local:validPort($srcPort))) then concat('invalid source port ''', $d[6], '''')
        else if (not(local:validPort($dstPort))) then concat('invalid destination port ''', $d[7], '''')
        else if ($proto = '') then 'missing protocol'
        else ()"/>

    <xsl:choose>
      <xsl:when test="exists($problem)">
        <xsl:message terminate="no">
          <xsl:value-of select="concat('WARN: dropping record ', count(preceding-sibling::rec:record) + 1,
              ': ', $problem, ' [', string-join($d, ','), ']')"/>
        </xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="protocol" select="local:protocol($proto)"/>
        <xsl:variable name="actionElement" select="
            if ($action = 'BLOCK') then 'Deny'
            else if ($action = 'ALLOW') then 'Permit'
            else 'Close'"/>
        <Event>
          <EventTime>
            <TimeCreated><xsl:value-of select="$time"/></TimeCreated>
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
            <TypeId>FirewallTraffic</TypeId>
            <Description>
              <xsl:value-of select="concat('Firewall ', $action, ' ', $proto, ' ',
                  local:endpoint($srcIp, $srcPort), ' -&gt; ', local:endpoint($dstIp, $dstPort))"/>
            </Description>
            <Network>
              <xsl:element name="{$actionElement}">
                <Source>
                  <Device>
                    <IPAddress><xsl:value-of select="$srcIp"/></IPAddress>
                  </Device>
                  <TransportProtocol><xsl:value-of select="$protocol"/></TransportProtocol>
                  <xsl:if test="$srcPort != ''">
                    <Port><xsl:value-of select="xs:integer($srcPort)"/></Port>
                  </xsl:if>
                </Source>
                <Destination>
                  <Device>
                    <IPAddress><xsl:value-of select="$dstIp"/></IPAddress>
                  </Device>
                  <TransportProtocol><xsl:value-of select="$protocol"/></TransportProtocol>
                  <xsl:if test="$dstPort != ''">
                    <Port><xsl:value-of select="xs:integer($dstPort)"/></Port>
                  </xsl:if>
                </Destination>
              </xsl:element>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

</xsl:stylesheet>
