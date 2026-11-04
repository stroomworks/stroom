<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translation of headerless firewall CSV records (Data Splitter records:2 output)
  into event-logging v4.1.0 XML.

  Column order (no header):
    1 time (ISO 8601 date-time)
    2 reporting device host name (the firewall/gateway)
    3 action: DENY | PERMIT | TEARDOWN
    4 source IP address
    5 destination IP address
    6 source port (may be empty, e.g. ICMP)
    7 destination port (may be empty, e.g. ICMP)
    8 transport protocol (tcp | udp | icmp | ...)

  Records that fail validation are not turned into events; an xsl:message
  is emitted (non-terminating) so the failure is visible in the pipeline.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:fn-local="urn:local-functions"
                xmlns="event-logging:3"
                exclude-result-prefixes="xs rec fn-local">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <!-- Values not present in the data; adjust for the real feed. -->
  <xsl:param name="systemName" as="xs:string" select="'Network Firewall'"/>
  <xsl:param name="systemEnvironment" as="xs:string" select="'Production'"/>
  <xsl:param name="generator" as="xs:string" select="'Firewall CSV log'"/>

  <xsl:variable name="ipv4Regex"
                select="'^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$'"/>

  <!-- ===== Root ===== -->
  <xsl:template match="/rec:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="event-logging:3 file://event-logging-v4.1.0.xsd"
            Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <!-- ===== Record ===== -->
  <xsl:template match="rec:record">
    <xsl:variable name="d" select="rec:data/normalize-space(@value)" as="xs:string*"/>
    <xsl:variable name="errors" as="xs:string*" select="fn-local:validate($d)"/>
    <xsl:choose>
      <xsl:when test="exists($errors)">
        <xsl:message terminate="no">
          <xsl:value-of select="concat('Record ', count(preceding-sibling::rec:record) + 1,
                                       ' skipped: ', string-join($errors, '; '))"/>
        </xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="event">
          <xsl:with-param name="d" select="$d"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="event">
    <xsl:param name="d" as="xs:string*"/>
    <xsl:variable name="time" select="$d[1]"/>
    <xsl:variable name="host" select="$d[2]"/>
    <xsl:variable name="action" select="upper-case($d[3])"/>
    <xsl:variable name="protocol" select="fn-local:protocol($d[8])"/>
    <xsl:variable name="actionElement" select="fn-local:action-element($action)"/>

    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="fn-local:format-time($time)"/>
        </TimeCreated>
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
          <xsl:value-of select="concat(fn-local:action-description($action), ' ',
                                       upper-case($d[8]), ' traffic from ',
                                       fn-local:endpoint($d[4], $d[6]), ' to ',
                                       fn-local:endpoint($d[5], $d[7]))"/>
        </Description>
        <Network>
          <xsl:element name="{$actionElement}" namespace="event-logging:3">
            <Source>
              <xsl:call-template name="location">
                <xsl:with-param name="ip" select="$d[4]"/>
                <xsl:with-param name="port" select="$d[6]"/>
                <xsl:with-param name="protocol" select="$protocol"/>
                <xsl:with-param name="rawProtocol" select="$d[8]"/>
              </xsl:call-template>
            </Source>
            <Destination>
              <xsl:call-template name="location">
                <xsl:with-param name="ip" select="$d[5]"/>
                <xsl:with-param name="port" select="$d[7]"/>
                <xsl:with-param name="protocol" select="$protocol"/>
                <xsl:with-param name="rawProtocol" select="$d[8]"/>
              </xsl:call-template>
            </Destination>
            <xsl:if test="$action = 'DENY'">
              <Outcome>
                <Success>true</Success>
                <Permitted>false</Permitted>
                <Description>Traffic denied by firewall</Description>
              </Outcome>
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
    <xsl:param name="rawProtocol" as="xs:string"/>
    <Device>
      <IPAddress><xsl:value-of select="fn-local:normalise-ip($ip)"/></IPAddress>
    </Device>
    <TransportProtocol><xsl:value-of select="$protocol"/></TransportProtocol>
    <xsl:if test="$port != ''">
      <Port><xsl:value-of select="xs:integer($port)"/></Port>
    </xsl:if>
    <xsl:if test="$protocol = 'Other'">
      <Data Name="Protocol" Value="{$rawProtocol}"/>
    </xsl:if>
  </xsl:template>

  <!-- ===== Functions ===== -->

  <!-- Returns a list of validation errors; empty if the record is valid. -->
  <xsl:function name="fn-local:validate" as="xs:string*">
    <xsl:param name="d" as="xs:string*"/>
    <xsl:choose>
      <xsl:when test="count($d) != 8">
        <xsl:sequence select="concat('expected 8 columns but found ', count($d))"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:if test="not($d[1] castable as xs:dateTime)
                      or not(timezone-from-dateTime(xs:dateTime($d[1])) instance of xs:dayTimeDuration)">
          <xsl:sequence select="concat('invalid or zone-less time [', $d[1], ']')"/>
        </xsl:if>
        <xsl:if test="$d[2] = ''">
          <xsl:sequence select="'missing device host name'"/>
        </xsl:if>
        <xsl:if test="empty(fn-local:action-element(upper-case($d[3])))">
          <xsl:sequence select="concat('unknown action [', $d[3], ']')"/>
        </xsl:if>
        <xsl:if test="not(fn-local:is-ip($d[4]))">
          <xsl:sequence select="concat('invalid source IP address [', $d[4], ']')"/>
        </xsl:if>
        <xsl:if test="not(fn-local:is-ip($d[5]))">
          <xsl:sequence select="concat('invalid destination IP address [', $d[5], ']')"/>
        </xsl:if>
        <xsl:if test="not(fn-local:is-port-or-empty($d[6]))">
          <xsl:sequence select="concat('invalid source port [', $d[6], ']')"/>
        </xsl:if>
        <xsl:if test="not(fn-local:is-port-or-empty($d[7]))">
          <xsl:sequence select="concat('invalid destination port [', $d[7], ']')"/>
        </xsl:if>
        <xsl:if test="$d[8] = ''">
          <xsl:sequence select="'missing protocol'"/>
        </xsl:if>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <xsl:function name="fn-local:action-element" as="xs:string?">
    <xsl:param name="action" as="xs:string"/>
    <xsl:choose>
      <xsl:when test="$action = 'PERMIT'">Permit</xsl:when>
      <xsl:when test="$action = 'DENY'">Deny</xsl:when>
      <xsl:when test="$action = 'TEARDOWN'">Close</xsl:when>
    </xsl:choose>
  </xsl:function>

  <xsl:function name="fn-local:action-description" as="xs:string">
    <xsl:param name="action" as="xs:string"/>
    <xsl:choose>
      <xsl:when test="$action = 'PERMIT'">Firewall permitted</xsl:when>
      <xsl:when test="$action = 'DENY'">Firewall denied</xsl:when>
      <xsl:otherwise>Firewall tore down connection for</xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <xsl:function name="fn-local:protocol" as="xs:string">
    <xsl:param name="raw" as="xs:string"/>
    <xsl:variable name="p" select="upper-case($raw)"/>
    <xsl:sequence select="if ($p = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $p else 'Other'"/>
  </xsl:function>

  <xsl:function name="fn-local:is-port-or-empty" as="xs:boolean">
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="$port = ''
                          or (matches($port, '^\d{1,5}$') and xs:integer($port) le 65535)"/>
  </xsl:function>

  <!-- IPv4 dotted quad, or IPv6 accepted by the schema's IPAddressSimpleType
       patterns (checked after lower-casing). -->
  <xsl:function name="fn-local:is-ip" as="xs:boolean">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:variable name="v6" select="lower-case($ip)"/>
    <xsl:sequence select="
      matches($ip, $ipv4Regex)
      or (contains($v6, ':')
          and matches($v6, '^[0-9a-f:.]+$')
          and fn-local:is-ipv6($v6))"/>
  </xsl:function>

  <!-- Structural IPv6 check: at most one '::', groups of 1-4 hex digits,
       optional trailing embedded IPv4, correct total group count. -->
  <xsl:function name="fn-local:is-ipv6" as="xs:boolean">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:variable name="dbl" select="count(tokenize($ip, '::')) - 1"/>
    <xsl:variable name="lastPart" select="tokenize($ip, ':')[last()]"/>
    <xsl:variable name="hasV4" select="contains($lastPart, '.')"/>
    <xsl:variable name="v4ok" select="not($hasV4) or matches($lastPart, $ipv4Regex)"/>
    <xsl:variable name="hexPart"
                  select="if ($hasV4) then substring($ip, 1, string-length($ip) - string-length($lastPart)) else $ip"/>
    <xsl:variable name="groups" select="tokenize($hexPart, ':')[. != '']"/>
    <xsl:variable name="groupsOk" select="every $g in $groups satisfies matches($g, '^[0-9a-f]{1,4}$')"/>
    <xsl:variable name="total" select="count($groups) + (if ($hasV4) then 2 else 0)"/>
    <xsl:variable name="noBadColons"
                  select="not(matches($ip, ':::'))
                          and (starts-with($ip, '::') or not(starts-with($ip, ':')))
                          and (ends-with($ip, '::') or not(ends-with($ip, ':')) or $hasV4)"/>
    <xsl:sequence select="$dbl le 1 and $v4ok and $groupsOk and $noBadColons
                          and (if ($dbl = 1) then $total le 7 else $total = 8)"/>
  </xsl:function>

  <xsl:function name="fn-local:normalise-ip" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:sequence select="if (contains($ip, ':')) then lower-case($ip) else $ip"/>
  </xsl:function>

  <xsl:function name="fn-local:endpoint" as="xs:string">
    <xsl:param name="ip" as="xs:string"/>
    <xsl:param name="port" as="xs:string"/>
    <xsl:sequence select="if ($port = '') then $ip
                          else if (contains($ip, ':')) then concat('[', $ip, ']:', $port)
                          else concat($ip, ':', $port)"/>
  </xsl:function>

  <!-- Normalise to UTC with millisecond precision: yyyy-MM-ddTHH:mm:ss.SSSZ -->
  <xsl:function name="fn-local:format-time" as="xs:string">
    <xsl:param name="raw" as="xs:string"/>
    <xsl:variable name="utc"
                  select="adjust-dateTime-to-timezone(xs:dateTime($raw), xs:dayTimeDuration('PT0S'))"/>
    <xsl:sequence select="format-dateTime($utc, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
  </xsl:function>

</xsl:stylesheet>
