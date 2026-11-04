<?xml version="1.1" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns="event-logging:3"
                exclude-result-prefixes="rec xs"
                version="2.0">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/rec:records">
    <Events xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            Version="4.1.0">
      <xsl:apply-templates select="rec:record"/>
    </Events>
  </xsl:template>

  <!-- Comment lines produce no event. -->
  <xsl:template match="rec:record[rec:data[@name = 'comment']]"/>

  <!-- Unparseable lines are reported and produce no event. -->
  <xsl:template match="rec:record[rec:data[@name = 'raw']]">
    <xsl:message>WARNING: Unparseable record skipped: <xsl:value-of select="rec:data[@name = 'raw']/@value"/></xsl:message>
  </xsl:template>

  <xsl:template match="rec:record">
    <xsl:variable name="time" select="rec:data[@name = 'time']/@value"/>
    <xsl:variable name="host" select="rec:data[@name = 'host']/@value"/>
    <xsl:variable name="action" select="rec:data[@name = 'action']/@value"/>
    <xsl:variable name="src" select="rec:data[@name = 'src']/@value"/>
    <xsl:variable name="dst" select="rec:data[@name = 'dst']/@value"/>
    <xsl:variable name="proto" select="rec:data[@name = 'proto']/@value"/>
    <xsl:variable name="sport" select="rec:data[@name = 'sport']/@value"/>
    <xsl:variable name="dport" select="rec:data[@name = 'dport']/@value"/>

    <xsl:variable name="problem" as="xs:string?">
      <xsl:choose>
        <xsl:when test="not($time castable as xs:dateTime)"><xsl:sequence select="concat('invalid time: ', $time)"/></xsl:when>
        <xsl:when test="not($action = ('ACCEPT', 'DROP'))"><xsl:sequence select="concat('unknown action: ', $action)"/></xsl:when>
        <xsl:when test="not(exists($src)) or not(rec:isValidAddress($src))"><xsl:sequence select="concat('invalid source address: ', $src)"/></xsl:when>
        <xsl:when test="$dst != '-' and exists($dst) and not(rec:isValidAddress($dst))"><xsl:sequence select="concat('invalid destination address: ', $dst)"/></xsl:when>
        <xsl:when test="not(rec:isValidPort($sport))"><xsl:sequence select="concat('invalid source port: ', $sport)"/></xsl:when>
        <xsl:when test="not(rec:isValidPort($dport))"><xsl:sequence select="concat('invalid destination port: ', $dport)"/></xsl:when>
      </xsl:choose>
    </xsl:variable>

    <xsl:choose>
      <xsl:when test="exists($problem)">
        <xsl:message>WARNING: Record dropped, <xsl:value-of select="$problem"/>: <xsl:value-of select="string-join(rec:data/concat(@name, '=', @value), ' ')"/></xsl:message>
      </xsl:when>
      <xsl:otherwise>
        <Event>
          <EventTime>
            <TimeCreated>
              <xsl:value-of select="format-dateTime(adjust-dateTime-to-timezone(xs:dateTime($time), xs:dayTimeDuration('PT0S')), '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
            </TimeCreated>
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
            <TypeId>
              <xsl:value-of select="if ($action = 'ACCEPT') then 'Firewall-Permit' else 'Firewall-Deny'"/>
            </TypeId>
            <Network>
              <xsl:variable name="body">
                <Source>
                  <xsl:call-template name="location">
                    <xsl:with-param name="address" select="$src"/>
                    <xsl:with-param name="proto" select="$proto"/>
                    <xsl:with-param name="port" select="$sport"/>
                  </xsl:call-template>
                </Source>
                <xsl:if test="exists($dst) and $dst != '-'">
                  <Destination>
                    <xsl:call-template name="location">
                      <xsl:with-param name="address" select="$dst"/>
                      <xsl:with-param name="proto" select="$proto"/>
                      <xsl:with-param name="port" select="$dport"/>
                    </xsl:call-template>
                  </Destination>
                </xsl:if>
              </xsl:variable>
              <xsl:choose>
                <xsl:when test="$action = 'ACCEPT'"><Permit><xsl:copy-of select="$body/*"/></Permit></xsl:when>
                <xsl:otherwise><Deny><xsl:copy-of select="$body/*"/></Deny></xsl:otherwise>
              </xsl:choose>
            </Network>
          </EventDetail>
        </Event>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Builds the children of a Source or Destination element. -->
  <xsl:template name="location">
    <xsl:param name="address"/>
    <xsl:param name="proto"/>
    <xsl:param name="port"/>
    <Device>
      <IPAddress><xsl:value-of select="$address"/></IPAddress>
    </Device>
    <xsl:if test="exists($proto) and $proto != '-'">
      <TransportProtocol>
        <xsl:value-of select="if ($proto = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $proto else 'Other'"/>
      </TransportProtocol>
    </xsl:if>
    <xsl:if test="exists($port) and $port != '-'">
      <Port><xsl:value-of select="$port"/></Port>
    </xsl:if>
  </xsl:template>

  <!-- Strict IPv4, loose IPv6. -->
  <xsl:function name="rec:isValidAddress" as="xs:boolean">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="
      (matches($value, '^\d{1,3}(\.\d{1,3}){3}$')
        and (every $octet in tokenize($value, '\.') satisfies xs:integer($octet) le 255))
      or matches($value, '^[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$')"/>
  </xsl:function>

  <!-- A port is valid if absent, '-', or an integer 0-65535. -->
  <xsl:function name="rec:isValidPort" as="xs:boolean">
    <xsl:param name="value" as="xs:string?"/>
    <xsl:sequence select="
      empty($value) or $value = '-'
      or (matches($value, '^\d{1,5}$') and xs:integer($value) le 65535)"/>
  </xsl:function>

</xsl:stylesheet>
