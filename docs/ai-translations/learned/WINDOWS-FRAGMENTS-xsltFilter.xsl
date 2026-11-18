<xsl:stylesheet version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:w="http://schemas.microsoft.com/win/2004/08/events/event"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns="event-logging:3"
    exclude-result-prefixes="xs w">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/records">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates select="w:Event"/>
    </Events>
  </xsl:template>

  <xsl:template match="w:Event">
    <xsl:variable name="sys" select="w:System"/>
    <xsl:variable name="data" select="w:EventData"/>
    <xsl:variable name="id" select="string($sys/w:EventID)"/>
    <xsl:variable name="time" select="string($sys/w:TimeCreated/@SystemTime)"/>
    <xsl:variable name="logonType" select="string($data/w:Data[@Name='LogonType'])"/>

    <Event>
      <EventTime>
        <TimeCreated>
          <xsl:value-of select="concat(substring($time, 1, 19), '.',
                                       substring(concat(substring-after($time, '.'), '000'), 1, 3), 'Z')"/>
        </TimeCreated>
      </EventTime>

      <EventSource>
        <System>
          <Name><xsl:value-of select="$sys/w:Provider/@Name"/></Name>
          <Environment><xsl:value-of select="$sys/w:Channel"/></Environment>
        </System>
        <Generator>Windows Event Log</Generator>
        <Device>
          <HostName><xsl:value-of select="$sys/w:Computer"/></HostName>
        </Device>
        <xsl:if test="$id = '4624'">
          <Client>
            <HostName><xsl:value-of select="$data/w:Data[@Name='WorkstationName']"/></HostName>
            <IPAddress><xsl:value-of select="$data/w:Data[@Name='IpAddress']"/></IPAddress>
            <Port><xsl:value-of select="$data/w:Data[@Name='IpPort']"/></Port>
          </Client>
        </xsl:if>
        <User>
          <Id>
            <xsl:choose>
              <xsl:when test="$id = '4688'">
                <xsl:value-of select="$data/w:Data[@Name='SubjectUserName']"/>
              </xsl:when>
              <xsl:otherwise>
                <xsl:value-of select="$data/w:Data[@Name='TargetUserName']"/>
              </xsl:otherwise>
            </xsl:choose>
          </Id>
        </User>
      </EventSource>

      <EventDetail>
        <TypeId><xsl:value-of select="$id"/></TypeId>
        <xsl:choose>

          <xsl:when test="$id = '4688'">
            <Description>
              <xsl:value-of select="string-join((
                  concat('EventRecordID: ', $sys/w:EventRecordID),
                  for $d in $data/w:Data[not(@Name = ('SubjectUserName', 'NewProcessName', 'CommandLine', 'NewProcessId'))]
                  return concat($d/@Name, ': ', $d)), '; ')"/>
            </Description>
            <Process>
              <Action>Execute</Action>
              <Type>Application</Type>
              <Command><xsl:value-of select="$data/w:Data[@Name='NewProcessName']"/></Command>
              <Arguments><xsl:value-of select="$data/w:Data[@Name='CommandLine']"/></Arguments>
              <ProcessId><xsl:value-of select="$data/w:Data[@Name='NewProcessId']"/></ProcessId>
            </Process>
          </xsl:when>

          <xsl:when test="$id = '4634'">
            <Description>
              <xsl:value-of select="string-join((
                  concat('EventRecordID: ', $sys/w:EventRecordID),
                  for $d in $data/w:Data[not(@Name = ('TargetUserName', 'LogonType'))]
                  return concat($d/@Name, ': ', $d)), '; ')"/>
            </Description>
            <Authenticate>
              <Action>Logoff</Action>
              <xsl:call-template name="logon-type"><xsl:with-param name="t" select="$logonType"/></xsl:call-template>
              <User>
                <Id><xsl:value-of select="$data/w:Data[@Name='TargetUserName']"/></Id>
              </User>
            </Authenticate>
          </xsl:when>

          <xsl:otherwise>
            <Description>
              <xsl:value-of select="string-join((
                  concat('EventRecordID: ', $sys/w:EventRecordID),
                  for $d in $data/w:Data[not(@Name = ('TargetUserName', 'LogonType', 'WorkstationName', 'IpAddress', 'IpPort'))]
                  return concat($d/@Name, ': ', $d)), '; ')"/>
            </Description>
            <Authenticate>
              <Action>Logon</Action>
              <xsl:call-template name="logon-type"><xsl:with-param name="t" select="$logonType"/></xsl:call-template>
              <User>
                <Id><xsl:value-of select="$data/w:Data[@Name='TargetUserName']"/></Id>
              </User>
            </Authenticate>
          </xsl:otherwise>

        </xsl:choose>
      </EventDetail>
    </Event>
  </xsl:template>

  <xsl:template name="logon-type">
    <xsl:param name="t"/>
    <xsl:variable name="name" select="
      if ($t = '2') then 'Interactive'
      else if ($t = '3') then 'Network'
      else if ($t = '4') then 'Batch'
      else if ($t = '5') then 'Service'
      else if ($t = '7') then 'Unlock'
      else if ($t = '8') then 'NetworkCleartext'
      else if ($t = '9') then 'NewCredentials'
      else if ($t = '10') then 'RemoteInteractive'
      else if ($t = '11') then 'CachedInteractive'
      else ''"/>
    <xsl:if test="$name != ''">
      <LogonType><xsl:value-of select="$name"/></LogonType>
    </xsl:if>
  </xsl:template>

</xsl:stylesheet>
