<?xml version="1.0" encoding="UTF-8"?>
<!-- Golden events for pilot case 02. Run with the candidate's OUTPUT as the source (see tools/run-checks.sh).
     Events are found by their UTC event time, compared as xs:dateTime. -->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:e="event-logging:3"
                exclude-result-prefixes="xs e">
  <xsl:output method="text"/>

  <xsl:function name="e:ev" as="element(e:Event)*">
    <xsl:param name="doc" as="document-node()"/>
    <xsl:param name="utc" as="xs:string"/>
    <xsl:sequence select="$doc//e:Event[e:EventTime/e:TimeCreated castable as xs:dateTime
                          and xs:dateTime(e:EventTime/e:TimeCreated) eq xs:dateTime($utc)]"/>
  </xsl:function>
  <xsl:function name="e:check">
    <xsl:param name="id" as="xs:string"/>
    <xsl:param name="what" as="xs:string"/>
    <xsl:param name="ok" as="xs:boolean"/>
    <xsl:value-of select="concat(if ($ok) then 'PASS ' else 'FAIL ', $id, '  ', $what, '&#10;')"/>
  </xsl:function>

  <xsl:template match="/">
    <xsl:variable name="d" select="."/>
    <xsl:variable name="all" select="//e:Event"/>

    <!-- G1: day-first date, no time zone in the log (UTC), BLOCK is Deny, first address is the source -->
    <xsl:variable name="g1" select="e:ev($d, '2026-11-04T10:00:01Z')"/>
    <xsl:sequence select="e:check('G1.1', '04/11/2026 10:00:01 is 4 November 10:00:01 UTC (day first, UTC)', count($g1) = 1)"/>
    <xsl:sequence select="e:check('G1.2', 'BLOCK is Network/Deny', exists($g1/e:EventDetail/e:Network/e:Deny))"/>
    <xsl:sequence select="e:check('G1.3', 'source 10.1.0.5:51514 and destination 198.51.100.7:443',
        $g1//e:Source/e:Device/e:IPAddress = '10.1.0.5' and $g1//e:Source//e:Port = 51514
        and $g1//e:Destination/e:Device/e:IPAddress = '198.51.100.7' and $g1//e:Destination//e:Port = 443)"/>
    <xsl:sequence select="e:check('G1.4', 'protocol is TCP', $g1//e:TransportProtocol = 'TCP')"/>
    <xsl:sequence select="e:check('G1.5', 'logging device is fw02 (as HostName or Name)', ($g1/e:EventSource/e:Device/e:HostName, $g1/e:EventSource/e:Device/e:Name) = 'fw02')"/>

    <!-- G2: THE TRAP. The first address is the source even though its port (3389) is a well-known service port
         and the second port (52100) is ephemeral. A port-based guess puts the source in the wrong place. -->
    <xsl:variable name="g2" select="e:ev($d, '2026-11-13T09:30:00Z')"/>
    <xsl:sequence select="e:check('G2.1', '13/11/2026 is 13 November (this row proves day-first)', count($g2) = 1)"/>
    <xsl:sequence select="e:check('G2.2', 'ALLOW is Network/Permit', exists($g2/e:EventDetail/e:Network/e:Permit))"/>
    <xsl:sequence select="e:check('G2.3', 'the FIRST address 198.51.100.9:3389 is the source (not the port guess)',
        $g2//e:Source/e:Device/e:IPAddress = '198.51.100.9' and $g2//e:Source//e:Port = 3389
        and not($g2//e:Destination/e:Device/e:IPAddress = '198.51.100.9'))"/>
    <xsl:sequence select="e:check('G2.4', 'the SECOND address 10.1.0.20:52100 is the destination',
        $g2//e:Destination/e:Device/e:IPAddress = '10.1.0.20' and $g2//e:Destination//e:Port = 52100)"/>

    <!-- G3: both ports are ephemeral, so no hint at all -->
    <xsl:variable name="g3" select="e:ev($d, '2026-11-04T10:00:03Z')"/>
    <xsl:sequence select="e:check('G3.1', 'UDP; source 10.1.0.7:40000, destination 10.1.0.8:40001',
        count($g3) = 1 and $g3//e:TransportProtocol = 'UDP' and $g3//e:Source/e:Device/e:IPAddress = '10.1.0.7'
        and $g3//e:Source//e:Port = 40000 and $g3//e:Destination/e:Device/e:IPAddress = '10.1.0.8'
        and $g3//e:Destination//e:Port = 40001)"/>

    <!-- G4: empty ports (not a dash) -->
    <xsl:variable name="g4" select="e:ev($d, '2026-11-04T10:00:04Z')"/>
    <xsl:sequence select="e:check('G4.1', 'ICMP with empty ports has no Port element', count($g4) = 1 and $g4//e:TransportProtocol = 'ICMP' and not($g4//e:Port))"/>

    <!-- G5: IPv6 link-local and multicast -->
    <xsl:variable name="g5" select="e:ev($d, '2026-11-04T10:00:05Z')"/>
    <xsl:sequence select="e:check('G5.1', 'IPv6 source fe80::1 and destination ff02::fb, UDP',
        count($g5) = 1 and $g5//e:Source/e:Device/e:IPAddress = 'fe80::1' and $g5//e:Destination/e:Device/e:IPAddress = 'ff02::fb'
        and $g5//e:TransportProtocol = 'UDP')"/>

    <!-- G6: RESET is not Permit or Deny. The intended mapping is Network/Close. -->
    <xsl:variable name="g6" select="e:ev($d, '2026-11-04T10:00:08Z')"/>
    <xsl:sequence select="e:check('G6.1', 'RESET is recorded as Network/Close, with the source and destination kept',
        count($g6) = 1 and exists($g6/e:EventDetail/e:Network/e:Close)
        and $g6//e:Source/e:Device/e:IPAddress = '10.1.0.5' and $g6//e:Destination/e:Device/e:IPAddress = '198.51.100.7')"/>

    <!-- Negative goldens -->
    <xsl:sequence select="e:check('N1', 'the invalid address 10.1.0.256 was not output, and no event at 10:00:06',
        not($all//text()[contains(., '10.1.0.256')]) and count(e:ev($d, '2026-11-04T10:00:06Z')) = 0)"/>
    <xsl:sequence select="e:check('N2', 'the truncated row (3 fields) produced no event', count(e:ev($d, '2026-11-04T10:00:07Z')) = 0)"/>
    <xsl:sequence select="e:check('N3', 'exactly 6 events were produced', count($all) = 6)"/>

    <!-- Consistency -->
    <xsl:sequence select="e:check('C1', 'every event has a non-empty TypeId', every $x in $all satisfies normalize-space($x/e:EventDetail/e:TypeId) != '')"/>
    <xsl:sequence select="e:check('C2', 'the same action always gets the same TypeId',
        count(distinct-values($all[e:EventDetail/e:Network/e:Deny]/e:EventDetail/e:TypeId)) le 1)"/>
  </xsl:template>
</xsl:stylesheet>
