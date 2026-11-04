<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translates Data Splitter output (records:2) for a firewall log into
  event-logging v4.1.0 Events (event-logging:3).
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:local="urn:firewall-log-translation"
                xmlns="event-logging:3"
                exclude-result-prefixes="rec xs local">

   <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

   <xsl:template match="/">
      <Events Version="4.1.0">
         <xsl:apply-templates select="rec:records/rec:record"/>
      </Events>
   </xsl:template>

   <xsl:template match="rec:record">
      <xsl:variable name="time" select="string(rec:data[@name='time']/@value)"/>
      <xsl:variable name="host" select="string(rec:data[@name='host']/@value)"/>
      <xsl:variable name="action" select="string(rec:data[@name='action']/@value)"/>
      <xsl:variable name="src" select="string(rec:data[@name='src']/@value)"/>
      <xsl:variable name="dst" select="string(rec:data[@name='dst']/@value)"/>
      <xsl:variable name="proto" select="string(rec:data[@name='proto']/@value)"/>
      <xsl:variable name="sport" select="string(rec:data[@name='sport']/@value)"/>
      <xsl:variable name="dport" select="string(rec:data[@name='dport']/@value)"/>
      <xsl:choose>
         <!-- Comment lines such as "# fw01 firewall log started" are skipped silently. -->
         <xsl:when test="not(rec:data[@name='time'])">
            <xsl:if test="not(rec:data[@name='comment'])">
               <xsl:message>Skipping unrecognised record: <xsl:value-of select="local:describe(.)"/></xsl:message>
            </xsl:if>
         </xsl:when>
         <xsl:when test="not(local:is-datetime($time)
                         and local:is-ipv4-or-ipv6($src)
                         and ($dst = '-' or local:is-ipv4-or-ipv6($dst))
                         and $action = ('DROP', 'ACCEPT'))">
            <xsl:message>Dropping invalid record: <xsl:value-of select="local:describe(.)"/></xsl:message>
         </xsl:when>
         <xsl:otherwise>
            <Event>
               <EventTime>
                  <TimeCreated>
                     <xsl:value-of select="local:utc-time($time)"/>
                  </TimeCreated>
               </EventTime>
               <EventSource>
                  <System>
                     <!-- Assumption: the schema requires System/Name and Environment; values not yet confirmed. -->
                     <Name>Firewall</Name>
                     <Environment>Unknown</Environment>
                  </System>
                  <Generator>Firewall</Generator>
                  <xsl:if test="$host != ''">
                     <Device>
                        <HostName>
                           <xsl:value-of select="$host"/>
                        </HostName>
                     </Device>
                  </xsl:if>
               </EventSource>
               <EventDetail>
                  <TypeId>
                     <xsl:value-of select="$action"/>
                  </TypeId>
                  <Network>
                        <xsl:element name="{if ($action = 'DROP') then 'Deny' else 'Permit'}" namespace="event-logging:3">
                           <Source>
                              <Device>
                                 <IPAddress>
                                    <xsl:value-of select="$src"/>
                                 </IPAddress>
                              </Device>
                              <TransportProtocol>
                                 <xsl:value-of select="local:protocol($proto)"/>
                              </TransportProtocol>
                              <xsl:if test="exists(local:port($sport))">
                                 <Port>
                                    <xsl:value-of select="local:port($sport)"/>
                                 </Port>
                              </xsl:if>
                           </Source>
                           <xsl:if test="$dst != '-' or exists(local:port($dport))">
                              <Destination>
                                 <xsl:if test="$dst != '-'">
                                    <Device>
                                       <IPAddress>
                                          <xsl:value-of select="$dst"/>
                                       </IPAddress>
                                    </Device>
                                 </xsl:if>
                                 <TransportProtocol>
                                    <xsl:value-of select="local:protocol($proto)"/>
                                 </TransportProtocol>
                                 <xsl:if test="exists(local:port($dport))">
                                    <Port>
                                       <xsl:value-of select="local:port($dport)"/>
                                    </Port>
                                 </xsl:if>
                              </Destination>
                           </xsl:if>
                           <xsl:if test="$action = 'DROP'">
                              <Outcome>
                                 <Permitted>false</Permitted>
                              </Outcome>
                           </xsl:if>
                        </xsl:element>
                  </Network>
               </EventDetail>
            </Event>
         </xsl:otherwise>
      </xsl:choose>
   </xsl:template>

   <xsl:function name="local:is-datetime" as="xs:boolean">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="$value castable as xs:dateTime"/>
   </xsl:function>

   <!-- Converts to UTC in the form yyyy-MM-ddThh:mm:ss.sssZ, as the schema requires. -->
   <xsl:function name="local:utc-time" as="xs:string">
      <xsl:param name="value" as="xs:string"/>
      <xsl:variable name="utc" as="xs:dateTime"
                    select="adjust-dateTime-to-timezone(xs:dateTime($value), xs:dayTimeDuration('PT0S'))"/>
      <xsl:sequence select="concat(substring(string($utc), 1, 19), '.000Z')"/>
   </xsl:function>

   <xsl:function name="local:is-ipv4-or-ipv6" as="xs:boolean">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="matches($value, '^((25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])$')
                            or matches($value, '^([0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}$')"/>
   </xsl:function>

   <!-- Returns the port number, or an empty sequence for "-" or any value outside 0-65535. -->
   <xsl:function name="local:port" as="xs:string?">
      <xsl:param name="value" as="xs:string"/>
      <xsl:choose>
         <xsl:when test="not(matches($value, '^[0-9]{1,5}$'))"/>
         <xsl:when test="xs:integer($value) le 65535">
            <xsl:sequence select="string(xs:integer($value))"/>
         </xsl:when>
      </xsl:choose>
   </xsl:function>

   <!-- Maps to the schema's TransportProtocol enumeration, using Other for anything else. -->
   <xsl:function name="local:protocol" as="xs:string">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="if ($value = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $value else 'Other'"/>
   </xsl:function>

   <xsl:function name="local:describe" as="xs:string">
      <xsl:param name="record" as="element()"/>
      <xsl:sequence select="string-join(for $d in $record/rec:data return concat($d/@name, '=', $d/@value), ' ')"/>
   </xsl:function>

</xsl:stylesheet>
