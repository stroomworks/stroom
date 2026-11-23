<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xpath-default-namespace="http://www.w3.org/2013/XSL/json"
                exclude-result-prefixes="xsl">

  <xsl:output method="xml" indent="yes"/>

  <xsl:template match="/">
    <Events xmlns="event-logging:3"
            xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd"
            Version="3.0.0">
      <xsl:for-each select="map/map/array[@key='events']/map">
        <xsl:variable name="kind" select="string[@key='kind']"/>
        <Event>
          <EventTime>
            <TimeCreated><xsl:value-of select="string[@key='time']"/></TimeCreated>
          </EventTime>
          <EventSource>
            <System>
              <Name>API-GATEWAY</Name>
              <Environment>Not specified</Environment>
            </System>
            <Generator>API-GATEWAY</Generator>
            <Device>
              <HostName><xsl:value-of select="string[@key='host']"/></HostName>
            </Device>
            <xsl:if test="map[@key='client']">
              <Client>
                <xsl:if test="map[@key='client']/string[@key='ip']">
                  <IPAddress><xsl:value-of select="map[@key='client']/string[@key='ip']"/></IPAddress>
                </xsl:if>
                <xsl:if test="map[@key='client']/number[@key='port']">
                  <Port><xsl:value-of select="map[@key='client']/number[@key='port']"/></Port>
                </xsl:if>
              </Client>
            </xsl:if>
            <xsl:if test="string[@key='user']">
              <User>
                <Id><xsl:value-of select="string[@key='user']"/></Id>
              </User>
            </xsl:if>
          </EventSource>
          <EventDetail>
            <TypeId>
              <xsl:choose>
                <xsl:when test="$kind = 'logout'">API-GATEWAY-LOGOFF</xsl:when>
                <xsl:otherwise>API-GATEWAY-LOGON</xsl:otherwise>
              </xsl:choose>
            </TypeId>
            <Authenticate>
              <Action>
                <xsl:choose>
                  <xsl:when test="$kind = 'logout'">Logoff</xsl:when>
                  <xsl:otherwise>Logon</xsl:otherwise>
                </xsl:choose>
              </Action>
              <User>
                <Id><xsl:value-of select="string[@key='user']"/></Id>
              </User>
              <xsl:if test="string[@key='outcome']">
                <Outcome>
                  <Success>
                    <xsl:value-of select="if (string[@key='outcome'] = 'success') then 'true' else 'false'"/>
                  </Success>
                </Outcome>
              </xsl:if>
              <xsl:if test="map[@key='session']/string[@key='id']">
                <Data Name="SessionId" Value="{map[@key='session']/string[@key='id']}"/>
              </xsl:if>
              <xsl:if test="map[@key='session']/number[@key='seconds']">
                <Data Name="SessionSeconds" Value="{map[@key='session']/number[@key='seconds']}"/>
              </xsl:if>
            </Authenticate>
          </EventDetail>
        </Event>
      </xsl:for-each>
    </Events>
  </xsl:template>

</xsl:stylesheet>
