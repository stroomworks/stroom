<?xml version="1.1" encoding="UTF-8"?>
<!--
  Translates headerless firewall CSV records (Data Splitter records:2 output)
  into event-logging v4.1.0 events.

  Column order:
    1 timestamp (dd/MM/yyyy HH:mm:ss, UTC)
    2 firewall host name
    3 action (BLOCK | ALLOW | RESET)
    4 source IP
    5 destination IP
    6 source port (may be empty, e.g. ICMP)
    7 destination port (may be empty, e.g. ICMP)
    8 protocol
-->
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:rec="records:2"
    xmlns:fw="urn:local:firewall-translation"
    xmlns="event-logging:3"
    exclude-result-prefixes="xsl xs rec fw"
    version="2.0">

  <xsl:output method="xml" indent="yes"/>

  <!-- IPv4: same octet pattern as IPAddressSimpleType in the schema. -->
  <xsl:variable name="ipv4Octet" select="'(25[0-5]|2[0-4]\d|[0-1]?\d?\d)'"/>
  <xsl:variable name="ipv4Regex"
                select="concat('^', $ipv4Octet, '(\.', $ipv4Octet, '){3}$')"/>

  <!-- IPv6: the compressed/uncompressed forms allowed by IPAddressSimpleType
       (lower-case hex only, as the schema requires). -->
  <xsl:variable name="h" select="'[0-9a-f]{1,4}'"/>
  <xsl:variable name="ipv6Regex" select="concat(
      '^(::',
      '|(', $h, ':){1,1}(:', $h, '){1,6}',
      '|(', $h, ':){1,2}(:', $h, '){1,5}',
      '|(', $h, ':){1,3}(:', $h, '){1,4}',
      '|(', $h, ':){1,4}(:', $h, '){1,3}',
      '|(', $h, ':){1,5}(:', $h, '){1,2}',
      '|(', $h, ':){1,6}(:', $h, '){1,1}',
      '|(', $h, ':){1,7}:',
      '|:(:', $h, '){1,7}',
      '|(', $h, ':){7}', $h,
      ')$')"/>

  <xsl:variable name="timestampRegex"
                select="'^(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})$'"/>

  <xsl:template match="/rec:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="fields" select="rec:data/string(@value)" as="xs:string*"/>
    <xsl:variable name="problem" select="fw:problem($fields)" as="xs:string?"/>
    <xsl:choose>
      <xsl:when test="exists($problem)">
        <xsl:message terminate="no">
          <warn xmlns="">
            <xsl:value-of select="concat('Record ', count(preceding-sibling::rec:record) + 1,
                ' dropped: ', $problem, ' [', string-join($fields, ','), ']')"/>
          </warn>
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
    <xsl:variable name="networkAction" select="fw:network-action($action)"/>
    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="fw:to-iso-utc($fields[1])"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Firewall</Name>
          <Environment>Production</Environment>
        </System>
        <Generator>Firewall</Generator>
        <Device>
          <HostName>
            <xsl:value-of select="normalize-space($fields[2])"/>
          </HostName>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId>FirewallTraffic</TypeId>
        <Description>
          <xsl:value-of select="fw:description($action)"/>
        </Description>
        <Network>
          <xsl:element name="{$networkAction}">
            <Source>
              <xsl:call-template name="endpoint">
                <xsl:with-param name="ip" select="$fields[4]"/>
                <xsl:with-param name="port" select="$fields[6]"/>
                <xsl:with-param name="protocol" select="$fields[8]"/>
              </xsl:call-template>
            </Source>
            <Destination>
              <xsl:call-template name="endpoint">
                <xsl:with-param name="ip" select="$fields[5]"/>
                <xsl:with-param name="port" select="$fields[7]"/>
                <xsl:with-param name="protocol" select="$fields[8]"/>
              </xsl:call-template>
            </Destination>
          </xsl:element>
        </Network>
      </EventDetail>
    </Event>
  </xsl:template>

  <xsl:template name="endpoint">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:param name="protocol" as="xs:string"/>
    <Device>
      <IPAddress>
        <xsl:value-of select="fw:normalise-ip($ip)"/>
      </IPAddress>
    </Device>
    <TransportProtocol>
      <xsl:value-of select="fw:protocol($protocol)"/>
    </TransportProtocol>
    <xsl:if test="normalize-space($port) != ''">
      <Port>
        <xsl:value-of select="xs:integer(normalize-space($port))"/>
      </Port>
    </xsl:if>
  </xsl:template>

  <!-- Returns a reason the record is invalid, or the empty sequence if it is valid. -->
  <xsl:function name="fw:problem" as="xs:string?">
    <xsl:param name="fields" as="xs:string*"/>
    <xsl:variable name="action" select="upper-case(normalize-space($fields[3]))"/>
    <xsl:choose>
      <xsl:when test="count($fields) lt 8">
        <xsl:sequence select="concat('expected 8 fields but found ', count($fields))"/>
      </xsl:when>
      <xsl:when test="not(matches(normalize-space($fields[1]), $timestampRegex))
                      or empty(fw:to-iso-utc($fields[1]))">
        <xsl:sequence select="concat('invalid timestamp &quot;', $fields[1], '&quot;')"/>
      </xsl:when>
      <xsl:when test="normalize-space($fields[2]) = ''">
        <xsl:sequence select="'missing firewall host name'"/>
      </xsl:when>
      <xsl:when test="empty(fw:network-action($action))">
        <xsl:sequence select="concat('unknown action &quot;', $fields[3], '&quot;')"/>
      </xsl:when>
      <xsl:when test="not(fw:is-valid-ip($fields[4]))">
        <xsl:sequence select="concat('invalid source IP address &quot;', $fields[4], '&quot;')"/>
      </xsl:when>
      <xsl:when test="not(fw:is-valid-ip($fields[5]))">
        <xsl:sequence select="concat('invalid destination IP address &quot;', $fields[5], '&quot;')"/>
      </xsl:when>
      <xsl:when test="not(fw:is-valid-port($fields[6]))">
        <xsl:sequence select="concat('invalid source port &quot;', $fields[6], '&quot;')"/>
      </xsl:when>
      <xsl:when test="not(fw:is-valid-port($fields[7]))">
        <xsl:sequence select="concat('invalid destination port &quot;', $fields[7], '&quot;')"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:sequence select="()"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <xsl:function name="fw:network-action" as="xs:string?">
    <xsl:param name="action" as="xs:string"/>
    <xsl:sequence select="
        if ($action = 'BLOCK') then 'Deny'
        else if ($action = 'ALLOW') then 'Permit'
        else if ($action = 'RESET') then 'Close'
        else ()"/>
  </xsl:function>

  <xsl:function name="fw:description" as="xs:string">
    <xsl:param name="action" as="xs:string"/>
    <xsl:sequence select="
        if ($action = 'BLOCK') then 'Firewall blocked network traffic'
        else if ($action = 'ALLOW') then 'Firewall allowed network traffic'
        else 'Firewall reset network connection'"/>
  </xsl:function>

  <xsl:function name="fw:protocol" as="xs:string">
    <xsl:param name="protocol" as="xs:string"/>
    <xsl:variable name="upper" select="upper-case(normalize-space($protocol))"/>
    <xsl:sequence select="
        if ($upper = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $upper else 'Other'"/>
  </xsl:function>

  <xsl:function name="fw:normalise-ip" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <!-- The schema only allows lower-case hex in IPv6 addresses. -->
    <xsl:sequence select="lower-case(normalize-space($ip))"/>
  </xsl:function>

  <xsl:function name="fw:is-valid-ip" as="xs:boolean">
    <xsl:param name="ip" as="xs:string?"/>
    <xsl:variable name="value" select="fw:normalise-ip(string($ip))"/>
    <xsl:sequence select="matches($value, $ipv4Regex) or matches($value, $ipv6Regex)"/>
  </xsl:function>

  <!-- An empty port is valid (e.g. ICMP); otherwise it must be 0-65535. -->
  <xsl:function name="fw:is-valid-port" as="xs:boolean">
    <xsl:param name="port" as="xs:string?"/>
    <xsl:variable name="value" select="normalize-space(string($port))"/>
    <xsl:sequence select="
        $value = ''
        or (matches($value, '^\d{1,5}$') and xs:integer($value) le 65535)"/>
  </xsl:function>

  <!-- Converts 'dd/MM/yyyy HH:mm:ss' (already UTC) to 'yyyy-MM-ddTHH:mm:ss.000Z'.
       Returns the empty sequence if the value is not a real date/time. -->
  <xsl:function name="fw:to-iso-utc" as="xs:string?">
    <xsl:param name="timestamp" as="xs:string"/>
    <xsl:variable name="value" select="normalize-space($timestamp)"/>
    <xsl:variable name="iso" select="replace($value, $timestampRegex, '$3-$2-$1T$4:$5:$6')"/>
    <xsl:sequence select="
        if (matches($value, $timestampRegex) and $iso castable as xs:dateTime)
        then concat($iso, '.000Z')
        else ()"/>
  </xsl:function>

</xsl:stylesheet>
