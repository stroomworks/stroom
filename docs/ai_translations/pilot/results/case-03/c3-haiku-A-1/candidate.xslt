<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translates Data Splitter records (records:2) for a headerless firewall CSV into
  event-logging v4.1.0 Events.

  CSV column order:
    1 timestamp (UTC, yyyy-MM-ddTHH:mm:ssZ)
    2 host name of the firewall
    3 action (PERMIT, DENY or TEARDOWN)
    4 source IP address
    5 destination IP address
    6 source port (empty for ICMP)
    7 destination port (empty for ICMP)
    8 protocol (tcp, udp or icmp)

  Records that fail validation are skipped and reported with xsl:message.
-->
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:local="urn:stroom:firewall-csv:local"
                exclude-result-prefixes="xs rec local">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:records/rec:record"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="position" as="xs:integer" select="count(preceding-sibling::rec:record) + 1"/>
    <xsl:variable name="fields" as="xs:string*"
                  select="for $d in rec:data return normalize-space(string($d/@value))"/>
    <xsl:choose>
      <xsl:when test="local:is-valid-record($fields)">
        <xsl:variable name="action" as="xs:string" select="upper-case($fields[3])"/>
        <xsl:variable name="protocol" as="xs:string" select="local:protocol-name(lower-case($fields[8]))"/>
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="local:timestamp($fields[1])"/>
            </TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>Firewall</Name>
              <Environment>Unknown</Environment>
            </System>
            <Generator>Firewall CSV import</Generator>
            <Device>
              <HostName>
                <xsl:value-of select="$fields[2]"/>
              </HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId>
              <xsl:value-of select="$action"/>
            </TypeId>
            <Description>
              <xsl:value-of select="local:description($action, $protocol, $fields)"/>
            </Description>
            <Network>
              <xsl:element name="{local:network-action($action)}" namespace="event-logging:3">
                <Source>
                  <xsl:call-template name="network-location">
                    <xsl:with-param name="ip" select="$fields[4]"/>
                    <xsl:with-param name="port" select="$fields[6]"/>
                    <xsl:with-param name="protocol" select="$protocol"/>
                  </xsl:call-template>
                </Source>
                <Destination>
                  <xsl:call-template name="network-location">
                    <xsl:with-param name="ip" select="$fields[5]"/>
                    <xsl:with-param name="port" select="$fields[7]"/>
                    <xsl:with-param name="protocol" select="$protocol"/>
                  </xsl:call-template>
                </Destination>
              </xsl:element>
            </Network>
          </EventDetail>
        </Event>
      </xsl:when>
      <xsl:otherwise>
        <xsl:message>
          <xsl:value-of select="concat('Skipping invalid record at position ', $position, ': ', string-join($fields, ' | '))"/>
        </xsl:message>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Emits the Device, TransportProtocol and Port of one end of a network action. -->
  <xsl:template name="network-location">
    <xsl:param name="ip" as="xs:string" required="yes"/>
    <xsl:param name="port" as="xs:string" required="yes"/>
    <xsl:param name="protocol" as="xs:string" required="yes"/>
    <Device>
      <IPAddress>
        <xsl:value-of select="lower-case($ip)"/>
      </IPAddress>
    </Device>
    <TransportProtocol>
      <xsl:value-of select="$protocol"/>
    </TransportProtocol>
    <xsl:if test="$port != ''">
      <Port>
        <xsl:value-of select="string(xs:integer($port))"/>
      </Port>
    </xsl:if>
  </xsl:template>

  <!-- A record is valid when it has 8 fields, a well-formed timestamp, a host name, a known action,
       valid IP addresses, a known protocol and ports that suit that protocol. -->
  <xsl:function name="local:is-valid-record" as="xs:boolean">
    <xsl:param name="f" as="xs:string*"/>
    <xsl:sequence select="
      if (count($f) != 8) then false()
      else if (not(local:is-valid-timestamp($f[1]))) then false()
      else if ($f[2] = '') then false()
      else if (empty(local:network-action(upper-case($f[3])))) then false()
      else if (not(local:is-ip-address($f[4]) and local:is-ip-address($f[5]))) then false()
      else if (empty(local:protocol-name(lower-case($f[8])))) then false()
      else local:are-ports-valid(local:protocol-name(lower-case($f[8])), $f[6], $f[7])"/>
  </xsl:function>

  <!-- Accepts yyyy-MM-ddTHH:mm:ssZ, whole seconds, that is a real date-time. -->
  <xsl:function name="local:is-valid-timestamp" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$')
                          and ($value castable as xs:dateTime)"/>
  </xsl:function>

  <!-- Converts a valid timestamp into the yyyy-MM-ddThh:mm:ss.sssZ form that event-logging requires. -->
  <xsl:function name="local:timestamp" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="concat(substring($value, 1, 19), '.000Z')"/>
  </xsl:function>

  <xsl:function name="local:is-ip-address" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="local:is-ipv4($value) or local:is-ipv6($value)"/>
  </xsl:function>

  <xsl:function name="local:is-ipv4" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$')"/>
  </xsl:function>

  <!-- Checks the textual form of an IPv6 address: hex groups of 1 to 4 digits, at most one '::',
       and eight groups in total when no '::' is used. IPv4-embedded forms are not accepted. -->
  <xsl:function name="local:is-ipv6" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="
      if (not(matches($value, '^[0-9A-Fa-f:]+$'))) then false()
      else if (contains($value, ':::')) then false()
      else if (contains($value, '::')) then
        (count(tokenize($value, '::')) = 2
         and count(tokenize($value, ':')[. != '']) le 7
         and (every $g in tokenize($value, ':')[. != ''] satisfies matches($g, '^[0-9A-Fa-f]{1,4}$')))
      else
        (count(tokenize($value, ':')) = 8
         and (every $g in tokenize($value, ':') satisfies matches($g, '^[0-9A-Fa-f]{1,4}$')))"/>
  </xsl:function>

  <!-- Maps a firewall action to the event-logging Network action element, or to an empty sequence. -->
  <xsl:function name="local:network-action" as="xs:string?">
    <xsl:param name="action" as="xs:string"/>
    <xsl:sequence select="
      if ($action = 'PERMIT') then 'Permit'
      else if ($action = 'DENY') then 'Deny'
      else if ($action = 'TEARDOWN') then 'Close'
      else ()"/>
  </xsl:function>

  <!-- Maps a CSV protocol (already lower-cased) to the event-logging protocol name, or to an empty sequence. -->
  <xsl:function name="local:protocol-name" as="xs:string?">
    <xsl:param name="protocol" as="xs:string"/>
    <xsl:sequence select="
      if ($protocol = 'tcp') then 'TCP'
      else if ($protocol = 'udp') then 'UDP'
      else if ($protocol = 'icmp') then 'ICMP'
      else ()"/>
  </xsl:function>

  <!-- TCP and UDP need two valid ports; ICMP must have no ports. -->
  <xsl:function name="local:are-ports-valid" as="xs:boolean">
    <xsl:param name="protocol" as="xs:string"/>
    <xsl:param name="source-port" as="xs:string"/>
    <xsl:param name="destination-port" as="xs:string"/>
    <xsl:sequence select="
      if ($protocol = ('TCP', 'UDP'))
      then (local:is-port($source-port) and local:is-port($destination-port))
      else ($source-port = '' and $destination-port = '')"/>
  </xsl:function>

  <xsl:function name="local:is-port" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^\d{1,5}$') and (xs:integer($value) le 65535)"/>
  </xsl:function>

  <!-- A human-readable summary, e.g. "DENY TCP from 198.51.100.7 port 443 to 10.2.0.5 port 51514". -->
  <xsl:function name="local:description" as="xs:string">
    <xsl:param name="action" as="xs:string"/>
    <xsl:param name="protocol" as="xs:string"/>
    <xsl:param name="f" as="xs:string*"/>
    <xsl:sequence select="concat($action, ' ', $protocol,
                          ' from ', local:endpoint($f[4], $f[6]),
                          ' to ', local:endpoint($f[5], $f[7]))"/>
  </xsl:function>

  <xsl:function name="local:endpoint" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="if ($port = '') then $ip else concat($ip, ' port ', $port)"/>
  </xsl:function>

</xsl:stylesheet>
