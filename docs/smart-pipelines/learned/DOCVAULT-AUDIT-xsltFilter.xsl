<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns="event-logging:3"
  exclude-result-prefixes="xsl">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/">
    <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
      <xsl:apply-templates select="//entry"/>
    </Events>
  </xsl:template>

  <xsl:template match="entry">
    <xsl:variable name="did" select="normalize-space(did)"/>
    <xsl:variable name="isAuth" select="$did = 'login' or $did = 'logout'"/>
    <Event>
      <EventTime>
        <TimeCreated><xsl:value-of select="normalize-space(when)"/></TimeCreated>
      </EventTime>
      <EventSource>
        <System>
          <Name>DocVault</Name>
          <Environment>Not recorded</Environment>
        </System>
        <Generator>DocVault</Generator>
        <Device>
          <HostName><xsl:value-of select="normalize-space(session/host)"/></HostName>
        </Device>
        <xsl:if test="normalize-space(session/client/@ip) != ''">
          <Client>
            <IPAddress><xsl:value-of select="normalize-space(session/client/@ip)"/></IPAddress>
          </Client>
        </xsl:if>
        <xsl:if test="normalize-space(actor/login) != ''">
          <User>
            <Id><xsl:value-of select="normalize-space(actor/login)"/></Id>
          </User>
        </xsl:if>
        <xsl:if test="normalize-space(actor/department) != ''">
          <Data Name="Department" Value="{normalize-space(actor/department)}"/>
        </xsl:if>
        <xsl:if test="not($isAuth)">
          <Data Name="EntryId" Value="{@id}"/>
        </xsl:if>
      </EventSource>
      <EventDetail>
        <xsl:choose>
          <xsl:when test="$isAuth">
            <TypeId>
              <xsl:value-of select="if ($did = 'login') then 'DOCVAULT_LOGON' else 'DOCVAULT_LOGOFF'"/>
            </TypeId>
            <Authenticate>
              <Action>
                <xsl:value-of select="if ($did = 'login') then 'Logon' else 'Logoff'"/>
              </Action>
              <User>
                <Id><xsl:value-of select="normalize-space(actor/login)"/></Id>
              </User>
              <Data Name="EntryId" Value="{@id}"/>
            </Authenticate>
          </xsl:when>
          <xsl:otherwise>
            <TypeId>DOCVAULT_OPEN</TypeId>
            <View>
              <File>
                <xsl:if test="normalize-space(document/@classification) != ''">
                  <Classification>
                    <Text><xsl:value-of select="normalize-space(document/@classification)"/></Text>
                  </Classification>
                </xsl:if>
                <xsl:if test="normalize-space(document/@path) != ''">
                  <Path><xsl:value-of select="normalize-space(document/@path)"/></Path>
                </xsl:if>
                <xsl:if test="normalize-space(document/@size) != ''">
                  <Size><xsl:value-of select="normalize-space(document/@size)"/></Size>
                </xsl:if>
              </File>
            </View>
          </xsl:otherwise>
        </xsl:choose>
      </EventDetail>
    </Event>
  </xsl:template>

</xsl:stylesheet>
