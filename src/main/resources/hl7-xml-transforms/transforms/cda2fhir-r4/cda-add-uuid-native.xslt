<?xml version="1.0" encoding="UTF-8"?>
<!-- This transform adds UUID extensions to to elements that need to become discrete resources when converted to FHIR. Used as a pre-processor in the cda2fhir.xslt file. -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns="http://hl7.org/fhir" xmlns:lcg="http://www.lantanagroup.com" xmlns:xslt="http://www.w3.org/1999/XSL/Transform" xmlns:cda="urn:hl7-org:v3"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:fhir="http://hl7.org/fhir" xmlns:math="http://exslt.org/math" xmlns:uuid="http://www.uuid.org" version="2.0">

    <xsl:template match="/" priority="-1">
        <xsl:apply-templates select="*" mode="add-uuids" />
    </xsl:template>

    <xsl:template match="cda:ClinicalDocument" mode="add-uuids">
        <xsl:copy>
            <xsl:if test="not(@lcg:uuid)">
                <xsl:attribute name="lcg:uuid">
                    <xsl:value-of select="lower-case(uuid:get-uuid(.))" />
                </xsl:attribute>
            </xsl:if>
            <xsl:apply-templates select="@* | node()" mode="add-uuids" />
            <lcg:binary>
                <xsl:attribute name="lcg:uuid">
                    <xsl:value-of select="lower-case(uuid:get-uuid(.))" />
                </xsl:attribute>
            </lcg:binary>
        </xsl:copy>
    </xsl:template>

    <xsl:template match="cda:*" mode="add-uuids">
        <xsl:copy>
            <xsl:if test="not(@lcg:uuid)">
                <xsl:attribute name="lcg:uuid">
                    <xsl:value-of select="lower-case(uuid:get-uuid(.))" />
                </xsl:attribute>
            </xsl:if>
            <xsl:apply-templates select="@* | node()" mode="add-uuids" />
        </xsl:copy>
    </xsl:template>

    <xsl:template match="@* | node()" mode="add-uuids">
        <xsl:copy>
            <xsl:apply-templates select="@* | node()" mode="add-uuids" />
        </xsl:copy>
    </xsl:template>


    <!-- SG 20211101: Code taken from here (and slightly updated): https://gist.github.com/azinneera/778f69ae6b0049b5edcd69da70072405 and https://github.com/opendatacz/TED_extractor/blob/master/xslt/uuid.xsl-->

    <!-- Functions in the uuid: namespace are used to calculate a UUID The method used is a derived timestamp method, which 
		is explained here: http://www.famkruithof.net/guid-uuid-timebased.html and here: http://www.ietf.org/rfc/rfc4122.txt -->
    <!-- Returns the UUID -->
    <xsl:function name="uuid:get-uuid" as="xs:string*">
        <xsl:param name="node" />
        <xsl:variable name="ts" select="uuid:ts-to-hex(uuid:generate-timestamp($node))" />
        <!-- SG 20211102: Updated uuid:generate-clock-id to take param node -->
        <xsl:value-of separator="-" select="substring($ts, 8, 8), substring($ts, 4, 4), string-join((uuid:get-uuid-version(), substring($ts, 1, 3)), ''), uuid:generate-clock-id($node), uuid:get-network-node()"
         />
    </xsl:function>

    <!-- SG 20211102 Not using this function, using param node -->
    <!-- internal aux. function with saxon, this creates a more-unique result with generate-id then when just using a variable containing a node -->
    <!--<xsl:function name="uuid:_get-node">
        <xsl:comment />
    </xsl:function>-->

    <!-- should return the next nr in sequence, but this can't be done in xslt. Instead, it returns a guaranteed unique number -->
    <xsl:function name="uuid:next-nr" as="xs:integer">
        <xsl:param name="node" />
        <xsl:sequence select="xs:integer(replace(generate-id($node), '\D', ''))" />
    </xsl:function>

    <!-- internal function for returning hex digits only -->
    <xsl:function name="uuid:_hex-only" as="xs:string">
        <xsl:param name="string" />
        <xsl:param name="count" />
        <xsl:sequence select="substring(replace($string, '[^0-9a-fA-F]', ''), 1, $count)" />
    </xsl:function>

    <!-- may as well be defined as returning the same seq each time -->
    <!--    <xsl:variable name="_clock" select="generate-id(uuid:_get-node())" />-->

    <!-- SG 20211102: Updated to not use the _clock variable and added the node param -->
    <xsl:function name="uuid:generate-clock-id" as="xs:string">
        <xsl:param name="node" />
        <xsl:sequence select="upper-case(uuid:_hex-only(generate-id($node), 4))" />
    </xsl:function>

    <!-- returns the network node, this one is 'random', but must be the same within calls. The least-significant bit must be 
		'1' when it is not a real MAC address (in this case it is set to '1') -->
    <xsl:function name="uuid:get-network-node" as="xs:string">
        <xsl:sequence select="uuid:_hex-only('09-17-3F-13-E4-C5', 12)" />
    </xsl:function>

    <!-- returns version, for timestamp uuids, this is "1" -->
    <xsl:function name="uuid:get-uuid-version" as="xs:string">
        <xsl:sequence select="'1'" />
    </xsl:function>

    <!-- Generates a timestamp of the amount of 100 nanosecond intervals from 15 October 1582, in UTC time. -->
    <xsl:function name="uuid:generate-timestamp">
        <xsl:param name="node" />
        <!-- date calculation automatically goes correct when you add the timezone information, in this case that is UTC. -->
        <xsl:variable name="duration-from-1582" as="xs:dayTimeDuration">
            <xsl:sequence select="current-dateTime() - xs:dateTime('1582-10-15T00:00:00.000Z')" />
        </xsl:variable>
        <xsl:variable name="random-offset" as="xs:integer">
            <!-- SG 20230214: Removing the mod from this calculation because this was causing the same number to be generated for multiple generate-id($node) values
           i.e. d1e11398, d1e1398 => 111398, 11398 => 111398 mod 10000 = 1398, 11398 mod 10000 = 1398
           I don't think it matters that we are adding a larger number onto the 100 nano second intervals, so this should work -->
            <!--<xsl:sequence select="uuid:next-nr($node) mod 10000" />-->
            <xsl:sequence select="uuid:next-nr($node)" />
        </xsl:variable>
        <!-- do the math to get the 100 nano second intervals -->
        <xsl:sequence
            select="(days-from-duration($duration-from-1582) * 24 * 60 * 60 + hours-from-duration($duration-from-1582) * 60 * 60 + minutes-from-duration($duration-from-1582) * 60 + seconds-from-duration($duration-from-1582)) * 1000 * 10000 + $random-offset"
         />
    </xsl:function>

    <!-- simple non-generalized function to convert from timestamp to hex -->
    <xsl:function name="uuid:ts-to-hex">
        <xsl:param name="dec-val" />
        <xsl:value-of separator="" select="
                for $i in 1 to 15
                return
                    (0 to 9, tokenize('A B C D E F', ' '))[$dec-val idiv xs:integer(math:power(16, 15 - $i)) mod 16 + 1]" />
    </xsl:function>

    <xsl:function name="math:power">
        <xsl:param name="base" />
        <xsl:param name="power" />
        <xsl:choose>
            <xsl:when test="$power &lt; 0 or contains(string($power), '.')">
                <xsl:message terminate="yes"> The XSLT template math:power doesn't support negative or fractional arguments. </xsl:message>
                <xsl:text>NaN</xsl:text>
            </xsl:when>
            <xsl:otherwise>
                <xsl:call-template name="math:_power">
                    <xsl:with-param name="base" select="$base" />
                    <xsl:with-param name="power" select="$power" />
                    <xsl:with-param name="result" select="1" />
                </xsl:call-template>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:function>

    <xsl:template name="math:_power">
        <xsl:param name="base" />
        <xsl:param name="power" />
        <xsl:param name="result" />
        <xsl:choose>
            <xsl:when test="$power = 0">
                <xsl:value-of select="$result" />
            </xsl:when>
            <xsl:otherwise>
                <xsl:call-template name="math:_power">
                    <xsl:with-param name="base" select="$base" />
                    <xsl:with-param name="power" select="$power - 1" />
                    <xsl:with-param name="result" select="$result * $base" />
                </xsl:call-template>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

</xsl:stylesheet>
