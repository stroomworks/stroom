<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translates Data Splitter records (records:2) from a headerless firewall CSV
  into event-logging v4.1.0 Events.

  CSV column order (assumed from the data):
    1 timestamp (ISO 8601, UTC)
    2 device name
    3 action (PERMIT | DENY | TEARDOWN)
    4 source IP
    5 destination IP
    6 source port (empty for ICMP)
    7 destination port (empty for ICMP)
    8 protocol (tcp | udp | icmp)

  Invalid records are skipped with an xsl:message warning.
-->
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:local="urn:stroom:firewall-translation"
                exclude-result-prefixes="rec xs local">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- Values that the schema requires but the log does not supply. Change here if needed. -->
  <xsl:variable name="local:system-name" select="'Firewall'" as="xs:string"/>
  <xsl:variable name="local:environment" select="'Unknown'" as="xs:string"/>
  <xsl:variable name="local:generator" select="'Firewall'" as="xs:string"/>
  <xsl:variable name="local:type-id" select="'Firewall'" as="xs:string"/>

  <!-- IPv4 pattern, anchored, as in the schema's IPAddressSimpleType. -->
  <xsl:variable name="local:ipv4-regex" as="xs:string"
                select="'^(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}$'"/>

  <!-- IPv6 patterns, taken from the schema's IPAddressSimpleType, combined and anchored. -->
  <xsl:variable name="local:ipv6-regex" as="xs:string"
                select="concat('^(',
                  '([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,6}', '|',
                  '([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,5}', '|',
                  '([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,4}', '|',
                  '([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,3}', '|',
                  '([0-9a-f]{1,4}:){1,5}(:[0-9a-f]{1,4}){1,2}', '|',
                  '([0-9a-f]{1,4}:){1,6}(:[0-9a-f]{1,4}){1,1}', '|',
                  '([0-9a-f]{1,4}:){1,7}:', '|',
                  ':(:[0-9a-f]{1,4}){1,7}', '|',
                  '([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}', '|',
                  '::(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  '([0-9a-f]{1,4}:){1,1}(:[0-9a-f]{1,4}){1,4}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  '([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,3}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  '([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,2}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  '([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,1}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  '([0-9a-f]{1,4}:){1,5}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  ':(:[0-9a-f]{1,4}){1,5}:(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}', '|',
                  '([0-9a-f]{1,4}:){6}(25[0-5]|2[0-4]\d|[0-1]?\d?\d)(\.(25[0-5]|2[0-4]\d|[0-1]?\d?\d)){3}',
                  ')$')"/>

  <!-- Root: one Events element per input file. -->
  <xsl:template match="/rec:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <!-- One record: either an Event or a skipped record with a warning. -->
  <xsl:template match="rec:record">
    <xsl:variable name="fields" as="xs:string*" select="for $d in rec:data return string($d/@value)"/>
    <xsl:choose>
      <xsl:when test="local:is-valid($fields)">
        <xsl:call-template name="event">
          <xsl:with-param name="fields" select="$fields"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:message>
          <xsl:value-of select="concat('Skipping invalid record at position ', position(),
                                       ': ', string-join($fields, ','))"/>
        </xsl:message>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Builds one Event from a valid set of fields. -->
  <xsl:template name="event">
    <xsl:param name="fields" as="xs:string*" required="yes"/>
    <Event>
      <EventTime>
        <TimeCreated>
          <!-- The schema requires milliseconds; the log has whole seconds. -->
          <xsl:value-of select="concat(substring-before($fields[1], 'Z'), '.000Z')"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name><xsl:value-of select="$local:system-name"/></Name>
          <Environment><xsl:value-of select="$local:environment"/></Environment>
        </System>
        <Generator><xsl:value-of select="$local:generator"/></Generator>
        <Device>
          <HostName><xsl:value-of select="$fields[2]"/></HostName>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="$local:type-id"/></TypeId>
        <Network>
          <xsl:call-template name="network-action">
            <xsl:with-param name="fields" select="$fields"/>
          </xsl:call-template>
        </Network>
      </EventDetail>
    </Event>
  </xsl:template>

  <!-- Chooses the Permit, Deny or Close element for the action. -->
  <xsl:template name="network-action">
    <xsl:param name="fields" as="xs:string*" required="yes"/>
    <xsl:choose>
      <xsl:when test="$fields[3] = 'PERMIT'">
        <Permit>
          <xsl:call-template name="network-endpoints">
            <xsl:with-param name="fields" select="$fields"/>
          </xsl:call-template>
        </Permit>
      </xsl:when>
      <xsl:when test="$fields[3] = 'DENY'">
        <Deny>
          <xsl:call-template name="network-endpoints">
            <xsl:with-param name="fields" select="$fields"/>
          </xsl:call-template>
        </Deny>
      </xsl:when>
      <xsl:otherwise>
        <!-- TEARDOWN -->
        <Close>
          <xsl:call-template name="network-endpoints">
            <xsl:with-param name="fields" select="$fields"/>
          </xsl:call-template>
        </Close>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Writes the Source and Destination of a network action. -->
  <xsl:template name="network-endpoints">
    <xsl:param name="fields" as="xs:string*" required="yes"/>
    <Source>
      <Device>
        <IPAddress><xsl:value-of select="$fields[4]"/></IPAddress>
      </Device>
      <TransportProtocol>
        <xsl:value-of select="local:protocol($fields[8])"/>
      </TransportProtocol>
      <xsl:if test="$fields[6] != ''">
        <Port><xsl:value-of select="$fields[6]"/></Port>
      </xsl:if>
    </Source>
    <Destination>
      <Device>
        <IPAddress><xsl:value-of select="$fields[5]"/></IPAddress>
      </Device>
      <TransportProtocol>
        <xsl:value-of select="local:protocol($fields[8])"/>
      </TransportProtocol>
      <xsl:if test="$fields[7] != ''">
        <Port><xsl:value-of select="$fields[7]"/></Port>
      </xsl:if>
    </Destination>
  </xsl:template>

  <!-- Maps the CSV protocol to the schema's NetworkProtocol values. -->
  <xsl:function name="local:protocol" as="xs:string">
    <xsl:param name="csv" as="xs:string"/>
    <xsl:value-of select="upper-case($csv)"/>
  </xsl:function>

  <!-- True if the record has eight fields and every field is valid. -->
  <xsl:function name="local:is-valid" as="xs:boolean">
    <xsl:param name="f" as="xs:string*"/>
    <xsl:sequence select="
      count($f) = 8
      and matches($f[1], '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$')
      and $f[2] != ''
      and $f[3] = ('PERMIT', 'DENY', 'TEARDOWN')
      and local:is-ip($f[4])
      and local:is-ip($f[5])
      and $f[8] = ('tcp', 'udp', 'icmp')
      and (
        if ($f[8] = ('tcp', 'udp'))
        then local:is-port($f[6]) and local:is-port($f[7])
        else ($f[6] = '' or local:is-port($f[6])) and ($f[7] = '' or local:is-port($f[7]))
      )"/>
  </xsl:function>

  <!-- True if the value is an IPv4 or IPv6 address. -->
  <xsl:function name="local:is-ip" as="xs:boolean">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:sequence select="matches($ip, $local:ipv4-regex) or matches($ip, $local:ipv6-regex)"/>
  </xsl:function>

  <!-- True if the value is an integer port number from 0 to 65535. -->
  <xsl:function name="local:is-port" as="xs:boolean">
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="if (matches($port, '^\d{1,5}$'))
                          then xs:integer($port) le 65535
                          else false()"/>
  </xsl:function>

</xsl:stylesheet>
