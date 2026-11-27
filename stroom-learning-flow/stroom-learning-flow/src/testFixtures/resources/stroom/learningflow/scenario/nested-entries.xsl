<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns="event-logging:3"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                exclude-result-prefixes="xsl"
                version="2.0">

  <xsl:template match="/">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates select="//entry"/>
    </Events>
  </xsl:template>

  <xsl:template match="entry">
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="when"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>DocVault</Name>
          <Environment>Test</Environment>
        </System>
        <Generator>XML</Generator>
        <Device>
          <Name><xsl:value-of select="session/host"/></Name>
        </Device>
        <User>
          <Id><xsl:value-of select="actor/login"/></Id>
        </User>
      </EventSource>
      <EventDetail>
        <TypeId><xsl:value-of select="did"/></TypeId>
        <Authenticate>
          <Action>Logon</Action>
          <User>
            <Id><xsl:value-of select="actor/login"/></Id>
          </User>
        </Authenticate>
      </EventDetail>
    </Event>
  </xsl:template>

</xsl:stylesheet>
