<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:fn="http://www.w3.org/2005/xpath-functions"
                xmlns:rec="records:2"
                xmlns="event-logging:3"
                exclude-result-prefixes="xs fn rec"
                version="2.0">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:variable name="ipv4-regex"
                select="'^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$'"/>
  <!-- Deliberately loose IPv6 check; the schema validation is the final judge. -->
  <xsl:variable name="ipv6-regex" select="'^[0-9a-f:]*:[0-9a-f:]*(:[0-9]{1,3}(\.[0-9]{1,3}){3})?$'"/>

  <xsl:template match="/rec:records">
    <Events Version="4.1.0">
      <xsl:apply-templates select="rec:record[rec:data[@name = 'src']]"/>
    </Events>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="time" select="normalize-space(rec:data[@name = 'time']/@value)"/>
    <xsl:variable name="host" select="normalize-space(rec:data[@name = 'host']/@value)"/>
    <xsl:variable name="action" select="upper-case(normalize-space(rec:data[@name = 'action']/@value))"/>
    <xsl:variable name="src" select="lower-case(normalize-space(rec:data[@name = 'src']/@value))"/>
    <xsl:variable name="dst" select="lower-case(normalize-space(rec:data[@name = 'dst']/@value))"/>
    <xsl:variable name="proto" select="upper-case(normalize-space(rec:data[@name = 'proto']/@value))"/>
    <xsl:variable name="sport" select="normalize-space(rec:data[@name = 'sport']/@value)"/>
    <xsl:variable name="dport" select="normalize-space(rec:data[@name = 'dport']/@value)"/>

    <!-- A record is only usable if it has a parseable time, a known action and a valid source address. -->
    <xsl:if test="$time castable as xs:dateTime
                  and $action = ('DROP', 'ACCEPT')
                  and (matches($src, $ipv4-regex) or matches($src, $ipv6-regex))">
      <Event>
        <EventTime>
          <TimeCreated>
            <xsl:value-of select="format-dateTime(
                adjust-dateTime-to-timezone(xs:dateTime($time), xs:dayTimeDuration('PT0S')),
                '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
          </TimeCreated>
        </EventTime>
        <EventSource>
          <System>
            <Name>Firewall</Name>
            <Environment>Production</Environment>
          </System>
          <Generator>fw01 firewall log</Generator>
          <Device>
            <xsl:if test="$host != ''">
              <HostName><xsl:value-of select="$host"/></HostName>
            </xsl:if>
          </Device>
        </EventSource>
        <EventDetail>
          <TypeId>
            <xsl:value-of select="if ($action = 'DROP') then 'Firewall-Drop' else 'Firewall-Accept'"/>
          </TypeId>
          <Description>
            <xsl:value-of select="if ($action = 'DROP') then 'Network traffic denied by firewall'
                                  else 'Network traffic permitted by firewall'"/>
          </Description>
          <Network>
            <xsl:element name="{if ($action = 'DROP') then 'Deny' else 'Permit'}">
              <Source>
                <xsl:call-template name="location">
                  <xsl:with-param name="ip" select="$src"/>
                  <xsl:with-param name="proto" select="$proto"/>
                  <xsl:with-param name="port" select="$sport"/>
                </xsl:call-template>
              </Source>
              <xsl:if test="matches($dst, $ipv4-regex) or matches($dst, $ipv6-regex)">
                <Destination>
                  <xsl:call-template name="location">
                    <xsl:with-param name="ip" select="$dst"/>
                    <xsl:with-param name="proto" select="$proto"/>
                    <xsl:with-param name="port" select="$dport"/>
                  </xsl:call-template>
                </Destination>
              </xsl:if>
              <Outcome>
                <Permitted><xsl:value-of select="$action = 'ACCEPT'"/></Permitted>
              </Outcome>
            </xsl:element>
          </Network>
        </EventDetail>
      </Event>
    </xsl:if>
  </xsl:template>

  <xsl:template name="location">
    <xsl:param name="ip"/>
    <xsl:param name="proto"/>
    <xsl:param name="port"/>
    <Device>
      <IPAddress><xsl:value-of select="$ip"/></IPAddress>
    </Device>
    <xsl:if test="$proto != '' and $proto != '-'">
      <TransportProtocol>
        <xsl:value-of select="if ($proto = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $proto else 'Other'"/>
      </TransportProtocol>
    </xsl:if>
    <xsl:if test="matches($port, '^[0-9]{1,5}$') and xs:integer($port) le 65535">
      <Port><xsl:value-of select="xs:integer($port)"/></Port>
    </xsl:if>
    <xsl:if test="$proto != '' and $proto != '-' and not($proto = ('TCP', 'UDP', 'ICMP', 'IGMP'))">
      <Data Name="Protocol" Value="{$proto}"/>
    </xsl:if>
  </xsl:template>
</xsl:stylesheet>
