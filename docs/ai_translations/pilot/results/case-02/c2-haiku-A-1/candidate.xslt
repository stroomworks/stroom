<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translates firewall CSV rows (no header), as split by the Data Splitter into
  records:2 <record>/<data> elements in CSV column order, into event-logging v4.1.0 Events.

  Column order:
    1 timestamp (DD/MM/YYYY HH:mm:ss, assumed UTC)
    2 firewall name
    3 action (ALLOW or BLOCK)
    4 source IP
    5 destination IP
    6 source port (blank for ICMP)
    7 destination port (blank for ICMP)
    8 protocol (tcp, udp or icmp)

  A record that fails validation is skipped and reported with xsl:message.
-->
<xsl:stylesheet version="2.0"
                xmlns="event-logging:3"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:r="records:2"
                xmlns:local="urn:stroom:firewall-translation"
                exclude-result-prefixes="r xs local">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/">
    <Events Version="4.1.0">
      <xsl:apply-templates select="/r:records/r:record[local:is-valid(.)]" mode="event"/>
      <xsl:for-each select="/r:records/r:record[not(local:is-valid(.))]">
        <xsl:message>
          <xsl:value-of select="concat('Skipped invalid record ', count(preceding-sibling::r:record) + 1)"/>
        </xsl:message>
      </xsl:for-each>
    </Events>
  </xsl:template>

  <xsl:template match="r:record" mode="event">
    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="local:iso-time(local:field(., 1))"/>
        </TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>Firewall</Name>
          <Environment>Unknown</Environment>
        </System>
        <Generator>
          <xsl:value-of select="local:field(., 2)"/>
        </Generator>
        <Device>
          <Name>
            <xsl:value-of select="local:field(., 2)"/>
          </Name>
        </Device>
      </EventSource>
      <EventDetail>
        <TypeId>network-traffic</TypeId>
        <Network>
          <xsl:element name="{local:outcome-element(.)}">
            <Source>
              <Device>
                <IPAddress>
                  <xsl:value-of select="local:field(., 4)"/>
                </IPAddress>
              </Device>
              <TransportProtocol>
                <xsl:value-of select="upper-case(local:field(., 8))"/>
              </TransportProtocol>
              <xsl:if test="local:field(., 6) != ''">
                <Port>
                  <xsl:value-of select="local:field(., 6)"/>
                </Port>
              </xsl:if>
            </Source>
            <Destination>
              <Device>
                <IPAddress>
                  <xsl:value-of select="local:field(., 5)"/>
                </IPAddress>
              </Device>
              <TransportProtocol>
                <xsl:value-of select="upper-case(local:field(., 8))"/>
              </TransportProtocol>
              <xsl:if test="local:field(., 7) != ''">
                <Port>
                  <xsl:value-of select="local:field(., 7)"/>
                </Port>
              </xsl:if>
            </Destination>
            <Outcome>
              <Permitted>
                <xsl:value-of select="local:is-allowed(.)"/>
              </Permitted>
            </Outcome>
          </xsl:element>
        </Network>
      </EventDetail>
    </Event>
  </xsl:template>

  <!-- Returns the trimmed value of the nth CSV column, or an empty string if absent. -->
  <xsl:function name="local:field" as="xs:string">
    <xsl:param name="record" as="element()"/>
    <xsl:param name="position" as="xs:integer"/>
    <xsl:sequence select="normalize-space(string(($record/r:data)[$position]/@value))"/>
  </xsl:function>

  <!-- A record is valid when every column is well formed and the combination is coherent. -->
  <xsl:function name="local:is-valid" as="xs:boolean">
    <xsl:param name="record" as="element()"/>
    <xsl:variable name="protocol" select="lower-case(local:field($record, 8))" as="xs:string"/>
    <xsl:variable name="srcPort" select="local:field($record, 6)" as="xs:string"/>
    <xsl:variable name="dstPort" select="local:field($record, 7)" as="xs:string"/>
    <xsl:sequence select="
        count($record/r:data) = 8
        and local:is-timestamp(local:field($record, 1))
        and local:field($record, 2) != ''
        and upper-case(local:field($record, 3)) = ('ALLOW', 'BLOCK')
        and local:is-ip(local:field($record, 4))
        and local:is-ip(local:field($record, 5))
        and local:is-port($srcPort)
        and local:is-port($dstPort)
        and (
              ($protocol = ('tcp', 'udp') and $srcPort != '' and $dstPort != '')
              or ($protocol = 'icmp' and $srcPort = '' and $dstPort = '')
            )
        "/>
  </xsl:function>

  <!-- Accepts DD/MM/YYYY HH:mm:ss where the values form a real date and time. -->
  <xsl:function name="local:is-timestamp" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="
        matches($value, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')
        and (local:iso-local($value) castable as xs:dateTime)
        "/>
  </xsl:function>

  <xsl:function name="local:is-ip" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="local:is-ipv4($value) or local:is-ipv6($value)"/>
  </xsl:function>

  <xsl:function name="local:is-ipv4" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$')"/>
  </xsl:function>

  <!-- Non-embedded IPv6 forms only, including the compressed "::" form. -->
  <xsl:function name="local:is-ipv6" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="matches($value, '^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|(([0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4})?::(([0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4})?)$')"/>
  </xsl:function>

  <!-- A port is either blank (ICMP) or a whole number from 0 to 65535. -->
  <xsl:function name="local:is-port" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="$value = '' or (matches($value, '^[0-9]{1,5}$') and xs:integer($value) le 65535)"/>
  </xsl:function>

  <!-- Converts DD/MM/YYYY HH:mm:ss to an xs:dateTime lexical form without a timezone. -->
  <xsl:function name="local:iso-local" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="concat(substring($value, 7, 4), '-', substring($value, 4, 2), '-', substring($value, 1, 2), 'T', substring($value, 12, 8))"/>
  </xsl:function>

  <!-- Converts DD/MM/YYYY HH:mm:ss (assumed UTC) to the yyyy-MM-ddTHH:mm:ss.sssZ form required by event-logging. -->
  <xsl:function name="local:iso-time" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="concat(local:iso-local($value), '.000Z')"/>
  </xsl:function>

  <xsl:function name="local:is-allowed" as="xs:string">
    <xsl:param name="record" as="element()"/>
    <xsl:sequence select="if (upper-case(local:field($record, 3)) = 'ALLOW') then 'true' else 'false'"/>
  </xsl:function>

  <!-- ALLOW maps to Permit and BLOCK maps to Deny. -->
  <xsl:function name="local:outcome-element" as="xs:string">
    <xsl:param name="record" as="element()"/>
    <xsl:sequence select="if (upper-case(local:field($record, 3)) = 'ALLOW') then 'Permit' else 'Deny'"/>
  </xsl:function>

</xsl:stylesheet>
