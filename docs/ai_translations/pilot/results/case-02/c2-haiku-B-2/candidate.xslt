<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:local="urn:local-functions"
                xmlns="event-logging:3"
                exclude-result-prefixes="rec xs local">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- Root: one Events element holding one Event per valid record. -->
  <xsl:template match="/">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:records/rec:record"/>
    </Events>
  </xsl:template>

  <!-- Valid records become an Event. Invalid records are skipped and reported. -->
  <xsl:template match="rec:record">
    <xsl:choose>
      <xsl:when test="local:is-valid(.)">
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="local:iso-time(local:field(., 1))"/>
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
                <xsl:value-of select="local:field(., 2)"/>
              </HostName>
            </Device>
          </EventSource>
          <EventDetail>
            <TypeId>FirewallEvent</TypeId>
            <Network>
              <xsl:call-template name="action-element">
                <xsl:with-param name="action" select="local:field(., 3)"/>
                <xsl:with-param name="record" select="."/>
              </xsl:call-template>
            </Network>
          </EventDetail>
        </Event>
      </xsl:when>
      <xsl:otherwise>
        <xsl:message>
          <xsl:value-of select="concat('Skipped invalid record at position ', position(), ': ', string-join(rec:data/@value, ','))"/>
        </xsl:message>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Emits the network action element (BLOCK=Deny, ALLOW=Permit, RESET=Close) with its Source and Destination. -->
  <xsl:template name="action-element">
    <xsl:param name="action" as="xs:string"/>
    <xsl:param name="record" as="element()"/>
    <xsl:variable name="element-name" as="xs:string"
                  select="if ($action = 'BLOCK') then 'Deny'
                          else if ($action = 'ALLOW') then 'Permit'
                          else 'Close'"/>
    <xsl:element name="{$element-name}">
      <xsl:call-template name="location">
        <xsl:with-param name="name" select="'Source'"/>
        <xsl:with-param name="ip" select="local:field($record, 4)"/>
        <xsl:with-param name="protocol" select="local:field($record, 8)"/>
        <xsl:with-param name="port" select="local:field($record, 6)"/>
      </xsl:call-template>
      <xsl:call-template name="location">
        <xsl:with-param name="name" select="'Destination'"/>
        <xsl:with-param name="ip" select="local:field($record, 5)"/>
        <xsl:with-param name="protocol" select="local:field($record, 8)"/>
        <xsl:with-param name="port" select="local:field($record, 7)"/>
      </xsl:call-template>
    </xsl:element>
  </xsl:template>

  <!-- Emits a Source or Destination: Device/IPAddress, then TransportProtocol, then Port (omitted when empty). -->
  <xsl:template name="location">
    <xsl:param name="name" as="xs:string"/>
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="protocol" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:element name="{$name}">
      <Device>
        <IPAddress>
          <xsl:value-of select="$ip"/>
        </IPAddress>
      </Device>
      <TransportProtocol>
        <xsl:value-of select="upper-case($protocol)"/>
      </TransportProtocol>
      <xsl:if test="$port != ''">
        <Port>
          <xsl:value-of select="xs:integer($port)"/>
        </Port>
      </xsl:if>
    </xsl:element>
  </xsl:template>

  <!-- Returns the value of the nth data element (1-based), or an empty string if absent. -->
  <xsl:function name="local:field" as="xs:string">
    <xsl:param name="record" as="element()"/>
    <xsl:param name="n" as="xs:integer"/>
    <xsl:sequence select="string($record/rec:data[$n]/@value)"/>
  </xsl:function>

  <!-- Converts dd/MM/yyyy HH:mm:ss (UTC) into the schema format yyyy-MM-ddTHH:mm:ss.000Z. -->
  <xsl:function name="local:iso-time" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="concat(substring($value, 7, 4), '-', substring($value, 4, 2), '-', substring($value, 1, 2),
                                 'T', substring($value, 12, 8), '.000Z')"/>
  </xsl:function>

  <!-- A record is valid when it has 8 fields with a valid time, action, IP addresses, ports and protocol. -->
  <xsl:function name="local:is-valid" as="xs:boolean">
    <xsl:param name="record" as="element()"/>
    <xsl:variable name="time" as="xs:string" select="local:field($record, 1)"/>
    <xsl:sequence select="
      count($record/rec:data) = 8
      and matches($time, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')
      and local:iso-time($time) castable as xs:dateTime
      and local:field($record, 3) = ('BLOCK', 'ALLOW', 'RESET')
      and local:is-ip(local:field($record, 4))
      and local:is-ip(local:field($record, 5))
      and local:is-port(local:field($record, 6))
      and local:is-port(local:field($record, 7))
      and local:field($record, 8) = ('tcp', 'udp', 'icmp', 'igmp')"/>
  </xsl:function>

  <!-- An empty port is allowed (the Port element is then omitted). Otherwise it must be a number from 0 to 65535. -->
  <xsl:function name="local:is-port" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="
      if ($value = '') then true()
      else if (matches($value, '^\d{1,5}$')) then xs:integer($value) le 65535
      else false()"/>
  </xsl:function>

  <xsl:function name="local:is-ip" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="local:is-ipv4($value) or local:is-ipv6($value)"/>
  </xsl:function>

  <xsl:function name="local:is-ipv4" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$')"/>
  </xsl:function>

  <!-- Approximate IPv6 check. The XSD validation is the authority. -->
  <xsl:function name="local:is-ipv6" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^[0-9A-Fa-f:]+$') and matches($value, ':') and count(tokenize($value, '::')) le 2"/>
  </xsl:function>

</xsl:stylesheet>
