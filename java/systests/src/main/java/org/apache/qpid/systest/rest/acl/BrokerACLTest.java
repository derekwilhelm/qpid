/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.systest.rest.acl;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.KeyStore;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.model.TrustStore;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.security.acl.AbstractACLTestCase;
import org.apache.qpid.server.security.auth.manager.AnonymousAuthenticationManagerFactory;
import org.apache.qpid.server.security.auth.manager.PlainPasswordFileAuthenticationManagerFactory;
import org.apache.qpid.systest.rest.QpidRestTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;
import org.apache.qpid.test.utils.TestFileUtils;
import org.apache.qpid.test.utils.TestSSLConstants;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;

public class BrokerACLTest extends QpidRestTestCase
{
    private static final String ALLOWED_USER = "user1";
    private static final String DENIED_USER = "user2";

    @Override
    protected void customizeConfiguration() throws ConfigurationException, IOException
    {
        super.customizeConfiguration();
        getRestTestHelper().configureTemporaryPasswordFile(this, ALLOWED_USER, DENIED_USER);

        AbstractACLTestCase.writeACLFileUtil(this, null,
                "ACL ALLOW-LOG ALL ACCESS MANAGEMENT",
                "ACL ALLOW-LOG " + ALLOWED_USER + " CONFIGURE BROKER",
                "ACL DENY-LOG " + DENIED_USER + " CONFIGURE BROKER",
                "ACL DENY-LOG ALL ALL");

        getBrokerConfiguration().setObjectAttribute(TestBrokerConfiguration.ENTRY_NAME_HTTP_MANAGEMENT,
                "httpBasicAuthenticationEnabled", true);
    }

    /* === AuthenticationProvider === */

    public void testCreateAuthenticationProviderAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String authenticationProviderName = getTestName();

        int responseCode = createAuthenticationProvider(authenticationProviderName);
        assertEquals("Provider creation should be allowed", 201, responseCode);

        assertAuthenticationProviderExists(authenticationProviderName);
    }

    public void testCreateAuthenticationProviderDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        String authenticationProviderName = getTestName();

        int responseCode = createAuthenticationProvider(authenticationProviderName);
        assertEquals("Provider creation should be denied", 403, responseCode);

        assertAuthenticationProviderDoesNotExist(authenticationProviderName);
    }

    public void testDeleteAuthenticationProviderAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String providerName = getTestName();

        int responseCode = createAuthenticationProvider(providerName);
        assertEquals("Provider creation should be allowed", 201, responseCode);

        assertAuthenticationProviderExists(providerName);

        responseCode = getRestTestHelper().submitRequest("/rest/authenticationprovider/" + providerName, "DELETE", null);
        assertEquals("Provider deletion should be allowed", 200, responseCode);

        assertAuthenticationProviderDoesNotExist(TEST2_VIRTUALHOST);
    }

    public void testDeleteAuthenticationProviderDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String providerName = getTestName();

        int responseCode = createAuthenticationProvider(providerName);
        assertEquals("Provider creation should be allowed", 201, responseCode);

        assertAuthenticationProviderExists(providerName);

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);
        responseCode = getRestTestHelper().submitRequest("/rest/authenticationprovider/" + providerName, "DELETE", null);
        assertEquals("Provider deletion should be denied", 403, responseCode);

        assertAuthenticationProviderExists(providerName);
    }

    public void testSetAuthenticationProviderAttributesAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String providerName = TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER;

        assertAuthenticationProviderExists(providerName);

        File file = TestFileUtils.createTempFile(this, ".users", "guest:guest\n" + ALLOWED_USER + ":" + ALLOWED_USER + "\n"
                + DENIED_USER + ":" + DENIED_USER);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(AuthenticationProvider.NAME, providerName);
        attributes.put(AuthenticationProvider.TYPE, PlainPasswordFileAuthenticationManagerFactory.PROVIDER_TYPE);
        attributes.put(PlainPasswordFileAuthenticationManagerFactory.ATTRIBUTE_PATH, file.getAbsolutePath());

        int responseCode = getRestTestHelper().submitRequest("/rest/authenticationprovider/" + providerName, "PUT", attributes);
        assertEquals("Setting of provider attribites should be allowed", 200, responseCode);
    }

    public void testSetAuthenticationProviderAttributesDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String providerName = TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER;

        Map<String, Object> providerData = getRestTestHelper().getJsonAsSingletonList(
                "/rest/authenticationprovider/" + providerName);

        File file = TestFileUtils.createTempFile(this, ".users", "guest:guest\n" + ALLOWED_USER + ":" + ALLOWED_USER + "\n"
                + DENIED_USER + ":" + DENIED_USER);

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(AuthenticationProvider.NAME, providerName);
        attributes.put(AuthenticationProvider.TYPE, AnonymousAuthenticationManagerFactory.PROVIDER_TYPE);
        attributes.put(PlainPasswordFileAuthenticationManagerFactory.ATTRIBUTE_PATH, file.getAbsolutePath());

        int responseCode = getRestTestHelper().submitRequest("/rest/authenticationprovider/" + providerName, "PUT", attributes);
        assertEquals("Setting of provider attribites should be allowed", 403, responseCode);

        Map<String, Object> provider = getRestTestHelper().getJsonAsSingletonList("/rest/authenticationprovider/" + providerName);
        assertEquals("Unexpected PATH attribute value",
                providerData.get(PlainPasswordFileAuthenticationManagerFactory.ATTRIBUTE_PATH),
                provider.get(PlainPasswordFileAuthenticationManagerFactory.ATTRIBUTE_PATH));
    }

    /* === VirtualHost === */

    public void testCreateVirtualHostAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String hostName = getTestName();

        int responseCode = createHost(hostName);
        assertEquals("Host creation should be allowed", 201, responseCode);

        assertVirtualHostExists(hostName);
    }

    public void testCreateVirtualHostDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        String hostName = getTestName();

        int responseCode = createHost(hostName);
        assertEquals("Host creation should be denied", 403, responseCode);

        assertVirtualHostDoesNotExist(hostName);
    }

    public void testDeleteVirtualHostAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        assertVirtualHostExists(TEST2_VIRTUALHOST);

        int responseCode = getRestTestHelper().submitRequest("/rest/virtualhost/" + TEST2_VIRTUALHOST, "DELETE", null);
        assertEquals("Host deletion should be allowed", 200, responseCode);

        assertVirtualHostDoesNotExist(TEST2_VIRTUALHOST);
    }

    public void testDeleteVirtualHostDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        assertVirtualHostExists(TEST2_VIRTUALHOST);

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        int responseCode = getRestTestHelper().submitRequest("/rest/virtualhost/" + TEST2_VIRTUALHOST, "DELETE", null);
        assertEquals("Host deletion should be denied", 403, responseCode);

        assertVirtualHostExists(TEST2_VIRTUALHOST);
    }

    /* === Port === */

    public void testCreatePortAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String portName = getTestName();

        int responseCode = createPort(portName);
        assertEquals("Port creation should be allowed", 201, responseCode);

        assertPortExists(portName);
    }

    public void testCreatePortDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        String portName = getTestName();

        int responseCode = createPort(portName);
        assertEquals("Port creation should be denied", 403, responseCode);

        assertPortDoesNotExist(portName);
    }

    public void testDeletePortDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String portName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        assertPortExists(portName);

        restartBrokerInManagementMode();

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "DELETE", null);
        assertEquals("Port deletion should be denied", 403, responseCode);

        assertPortExists(portName);
    }

    public void testDeletePortAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String portName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        assertPortExists(portName);

        restartBrokerInManagementMode();

        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "DELETE", null);
        assertEquals("Port deletion should be allowed", 200, responseCode);

        assertPortDoesNotExist(portName);
    }

    public void testSetPortAttributesAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String portName = getTestName();

        int responseCode = createPort(portName);
        assertEquals("Port creation should be allowed", 201, responseCode);

        assertPortExists(portName);

        restartBrokerInManagementMode();

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.AUTHENTICATION_PROVIDER, ANONYMOUS_AUTHENTICATION_PROVIDER);
        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Setting of port attribites should be allowed", 200, responseCode);

        Map<String, Object> port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + portName);
        assertEquals("Unexpected authentication provider attribute value", ANONYMOUS_AUTHENTICATION_PROVIDER,
                port.get(Port.AUTHENTICATION_PROVIDER));
    }

    public void testSetPortAttributesDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String portName = getTestName();

        int responseCode = createPort(portName);
        assertEquals("Port creation should be allowed", 201, responseCode);

        assertPortExists(portName);

        restartBrokerInManagementMode();

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.PROTOCOLS, Arrays.asList(Protocol.AMQP_0_9));
        attributes.put(Port.AUTHENTICATION_PROVIDER, ANONYMOUS_AUTHENTICATION_PROVIDER);
        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Setting of port attribites should be denied", 403, responseCode);

        Map<String, Object> port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + portName);
        assertEquals("Unexpected authentication provider attribute value",
                TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER, port.get(Port.AUTHENTICATION_PROVIDER));
    }

    /* === KeyStore === */

    public void testCreateKeyStoreAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String keyStoreName = getTestName();

        assertKeyStoreExistence(keyStoreName, false);

        int responseCode = createKeyStore(keyStoreName, "app1");
        assertEquals("keyStore creation should be allowed", 201, responseCode);

        assertKeyStoreExistence(keyStoreName, true);
    }

    public void testCreateKeyStoreDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        String keyStoreName = getTestName();

        assertKeyStoreExistence(keyStoreName, false);

        int responseCode = createKeyStore(keyStoreName, "app1");
        assertEquals("keyStore creation should be allowed", 403, responseCode);

        assertKeyStoreExistence(keyStoreName, false);
    }

    public void testDeleteKeyStoreDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String keyStoreName = getTestName();

        assertKeyStoreExistence(keyStoreName, false);

        int responseCode = createKeyStore(keyStoreName, "app1");
        assertEquals("keyStore creation should be allowed", 201, responseCode);

        assertKeyStoreExistence(keyStoreName, true);

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        responseCode = getRestTestHelper().submitRequest("/rest/keystore/" + keyStoreName, "DELETE", null);
        assertEquals("keystore deletion should be denied", 403, responseCode);

        assertKeyStoreExistence(keyStoreName, true);
    }

    public void testDeleteKeyStoreAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String keyStoreName = getTestName();

        assertKeyStoreExistence(keyStoreName, false);

        int responseCode = createKeyStore(keyStoreName, "app1");
        assertEquals("keyStore creation should be allowed", 201, responseCode);

        assertKeyStoreExistence(keyStoreName, true);

        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        responseCode = getRestTestHelper().submitRequest("/rest/keystore/" + keyStoreName, "DELETE", null);
        assertEquals("keystore deletion should be allowed", 200, responseCode);

        assertKeyStoreExistence(keyStoreName, false);
    }

    public void testSetKeyStoreAttributesAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String keyStoreName = getTestName();
        String initialCertAlias = "app1";
        String updatedCertAlias = "app2";

        assertKeyStoreExistence(keyStoreName, false);

        int responseCode = createKeyStore(keyStoreName, initialCertAlias);
        assertEquals("keyStore creation should be allowed", 201, responseCode);

        assertKeyStoreExistence(keyStoreName, true);
        Map<String, Object> keyStore = getRestTestHelper().getJsonAsSingletonList("/rest/keystore/" + keyStoreName);
        assertEquals("Unexpected certificateAlias attribute value", initialCertAlias, keyStore.get(KeyStore.CERTIFICATE_ALIAS));

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(KeyStore.NAME, keyStoreName);
        attributes.put(KeyStore.CERTIFICATE_ALIAS, updatedCertAlias);
        responseCode = getRestTestHelper().submitRequest("/rest/keystore/" + keyStoreName, "PUT", attributes);
        assertEquals("Setting of keystore attributes should be allowed", 200, responseCode);

        keyStore = getRestTestHelper().getJsonAsSingletonList("/rest/keystore/" + keyStoreName);
        assertEquals("Unexpected certificateAlias attribute value", updatedCertAlias, keyStore.get(KeyStore.CERTIFICATE_ALIAS));
    }

    public void testSetKeyStoreAttributesDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String keyStoreName = getTestName();
        String initialCertAlias = "app1";
        String updatedCertAlias = "app2";

        assertKeyStoreExistence(keyStoreName, false);

        int responseCode = createKeyStore(keyStoreName, initialCertAlias);
        assertEquals("keyStore creation should be allowed", 201, responseCode);

        assertKeyStoreExistence(keyStoreName, true);
        Map<String, Object> keyStore = getRestTestHelper().getJsonAsSingletonList("/rest/keystore/" + keyStoreName);
        assertEquals("Unexpected certificateAlias attribute value", initialCertAlias, keyStore.get(KeyStore.CERTIFICATE_ALIAS));

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(KeyStore.NAME, keyStoreName);
        attributes.put(KeyStore.CERTIFICATE_ALIAS, updatedCertAlias);
        responseCode = getRestTestHelper().submitRequest("/rest/keystore/" + keyStoreName, "PUT", attributes);
        assertEquals("Setting of keystore attributes should be denied", 403, responseCode);

        keyStore = getRestTestHelper().getJsonAsSingletonList("/rest/keystore/" + keyStoreName);
        assertEquals("Unexpected certificateAlias attribute value", initialCertAlias, keyStore.get(KeyStore.CERTIFICATE_ALIAS));
    }

    /* === TrustStore === */

    public void testCreateTrustStoreAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String trustStoreName = getTestName();

        assertTrustStoreExistence(trustStoreName, false);

        int responseCode = createTrustStore(trustStoreName, false);
        assertEquals("trustStore creation should be allowed", 201, responseCode);

        assertTrustStoreExistence(trustStoreName, true);
    }

    public void testCreateTrustStoreDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        String trustStoreName = getTestName();

        assertTrustStoreExistence(trustStoreName, false);

        int responseCode = createTrustStore(trustStoreName, false);
        assertEquals("trustStore creation should be allowed", 403, responseCode);

        assertTrustStoreExistence(trustStoreName, false);
    }

    public void testDeleteTrustStoreDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String trustStoreName = getTestName();

        assertTrustStoreExistence(trustStoreName, false);

        int responseCode = createTrustStore(trustStoreName, false);
        assertEquals("trustStore creation should be allowed", 201, responseCode);

        assertTrustStoreExistence(trustStoreName, true);

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        responseCode = getRestTestHelper().submitRequest("/rest/truststore/" + trustStoreName, "DELETE", null);
        assertEquals("truststore deletion should be denied", 403, responseCode);

        assertTrustStoreExistence(trustStoreName, true);
    }

    public void testDeleteTrustStoreAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String trustStoreName = getTestName();

        assertTrustStoreExistence(trustStoreName, false);

        int responseCode = createTrustStore(trustStoreName, false);
        assertEquals("trustStore creation should be allowed", 201, responseCode);

        assertTrustStoreExistence(trustStoreName, true);

        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        responseCode = getRestTestHelper().submitRequest("/rest/truststore/" + trustStoreName, "DELETE", null);
        assertEquals("truststore deletion should be allowed", 200, responseCode);

        assertTrustStoreExistence(trustStoreName, false);
    }

    public void testSetTrustStoreAttributesAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String trustStoreName = getTestName();
        boolean initialPeersOnly = false;
        boolean updatedPeersOnly = true;

        assertTrustStoreExistence(trustStoreName, false);

        int responseCode = createTrustStore(trustStoreName, initialPeersOnly);
        assertEquals("trustStore creation should be allowed", 201, responseCode);

        assertTrustStoreExistence(trustStoreName, true);
        Map<String, Object> trustStore = getRestTestHelper().getJsonAsSingletonList("/rest/truststore/" + trustStoreName);
        assertEquals("Unexpected peersOnly attribute value", initialPeersOnly, trustStore.get(TrustStore.PEERS_ONLY));

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(TrustStore.NAME, trustStoreName);
        attributes.put(TrustStore.PEERS_ONLY, updatedPeersOnly);
        responseCode = getRestTestHelper().submitRequest("/rest/truststore/" + trustStoreName, "PUT", attributes);
        assertEquals("Setting of truststore attributes should be allowed", 200, responseCode);

        trustStore = getRestTestHelper().getJsonAsSingletonList("/rest/truststore/" + trustStoreName);
        assertEquals("Unexpected peersOnly attribute value", updatedPeersOnly, trustStore.get(TrustStore.PEERS_ONLY));
    }

    public void testSetTrustStoreAttributesDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String trustStoreName = getTestName();
        boolean initialPeersOnly = false;
        boolean updatedPeersOnly = true;

        assertTrustStoreExistence(trustStoreName, false);

        int responseCode = createTrustStore(trustStoreName, initialPeersOnly);
        assertEquals("trustStore creation should be allowed", 201, responseCode);

        assertTrustStoreExistence(trustStoreName, true);
        Map<String, Object> trustStore = getRestTestHelper().getJsonAsSingletonList("/rest/truststore/" + trustStoreName);
        assertEquals("Unexpected peersOnly attribute value", initialPeersOnly, trustStore.get(TrustStore.PEERS_ONLY));

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(TrustStore.NAME, trustStoreName);
        attributes.put(TrustStore.PEERS_ONLY, updatedPeersOnly);
        responseCode = getRestTestHelper().submitRequest("/rest/truststore/" + trustStoreName, "PUT", attributes);
        assertEquals("Setting of truststore attributes should be denied", 403, responseCode);

        trustStore = getRestTestHelper().getJsonAsSingletonList("/rest/truststore/" + trustStoreName);
        assertEquals("Unexpected peersOnly attribute value", initialPeersOnly, trustStore.get(TrustStore.PEERS_ONLY));
    }

    /* === Broker === */

    public void testSetBrokerAttributesAllowed() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String defaultAuthenticationProvider = TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER;
        Map<String, Object> brokerAttributes = getRestTestHelper().getJsonAsSingletonList("/rest/broker");
        assertEquals("Unexpected authentication provider", defaultAuthenticationProvider,
                brokerAttributes.get(Broker.DEFAULT_AUTHENTICATION_PROVIDER));
        restartBrokerInManagementMode();

        Map<String, Object> newAttributes = new HashMap<String, Object>();
        newAttributes.put(Broker.DEFAULT_AUTHENTICATION_PROVIDER, ANONYMOUS_AUTHENTICATION_PROVIDER);
        int responseCode = getRestTestHelper().submitRequest("/rest/broker", "PUT", newAttributes);
        assertEquals("Setting of port attribites should be allowed", 200, responseCode);

        brokerAttributes = getRestTestHelper().getJsonAsSingletonList("/rest/broker");
        assertEquals("Unexpected default authentication provider attribute value", ANONYMOUS_AUTHENTICATION_PROVIDER,
                brokerAttributes.get(Broker.DEFAULT_AUTHENTICATION_PROVIDER));
    }

    public void testSetBrokerAttributesDenied() throws Exception
    {
        getRestTestHelper().setUsernameAndPassword(ALLOWED_USER, ALLOWED_USER);

        String defaultAuthenticationProvider = TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER;

        Map<String, Object> brokerAttributes = getRestTestHelper().getJsonAsSingletonList("/rest/broker");
        assertEquals("Unexpected authentication provider", defaultAuthenticationProvider,
                brokerAttributes.get(Broker.DEFAULT_AUTHENTICATION_PROVIDER));
        restartBrokerInManagementMode();

        getRestTestHelper().setUsernameAndPassword(DENIED_USER, DENIED_USER);
        Map<String, Object> newAttributes = new HashMap<String, Object>();
        newAttributes.put(Broker.DEFAULT_AUTHENTICATION_PROVIDER, ANONYMOUS_AUTHENTICATION_PROVIDER);
        int responseCode = getRestTestHelper().submitRequest("/rest/broker", "PUT", newAttributes);
        assertEquals("Setting of port attribites should be allowed", 403, responseCode);

        brokerAttributes = getRestTestHelper().getJsonAsSingletonList("/rest/broker");
        assertEquals("Unexpected default authentication provider attribute value", defaultAuthenticationProvider,
                brokerAttributes.get(Broker.DEFAULT_AUTHENTICATION_PROVIDER));
    }

    private int createPort(String portName) throws Exception
    {
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.PORT, findFreePort());
        attributes.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER);

        return getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
    }

    private void assertPortExists(String portName) throws Exception
    {
        assertPortExistence(portName, true);
    }

    private void assertPortDoesNotExist(String portName) throws Exception
    {
        assertPortExistence(portName, false);
    }

    private void assertPortExistence(String portName, boolean exists) throws Exception
    {
        List<Map<String, Object>> hosts = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertEquals("Unexpected result", exists, !hosts.isEmpty());
    }

    private void assertKeyStoreExistence(String keyStoreName, boolean exists) throws Exception
    {
        List<Map<String, Object>> keyStores = getRestTestHelper().getJsonAsList("/rest/keystore/" + keyStoreName);
        assertEquals("Unexpected result", exists, !keyStores.isEmpty());
    }

    private void assertTrustStoreExistence(String trustStoreName, boolean exists) throws Exception
    {
        List<Map<String, Object>> trustStores = getRestTestHelper().getJsonAsList("/rest/truststore/" + trustStoreName);
        assertEquals("Unexpected result", exists, !trustStores.isEmpty());
    }

    private int createHost(String hostName) throws Exception
    {
        Map<String, Object> hostData = new HashMap<String, Object>();
        hostData.put(VirtualHost.NAME, hostName);
        hostData.put(VirtualHost.STORE_PATH, getStoreLocation(hostName));
        hostData.put(VirtualHost.STORE_TYPE, getTestProfileMessageStoreType());

        return getRestTestHelper().submitRequest("/rest/virtualhost/" + hostName, "PUT", hostData);
    }

    private void assertVirtualHostDoesNotExist(String hostName) throws Exception
    {
        assertVirtualHostExistence(hostName, false);
    }

    private void assertVirtualHostExists(String hostName) throws Exception
    {
        assertVirtualHostExistence(hostName, true);
    }

    private void assertVirtualHostExistence(String hostName, boolean exists) throws Exception
    {
        List<Map<String, Object>> hosts = getRestTestHelper().getJsonAsList("/rest/virtualhost/" + hostName);
        assertEquals("Unexpected result", exists, !hosts.isEmpty());
    }

    private String getStoreLocation(String hostName)
    {
        return new File(TMP_FOLDER, "store-" + hostName + "-" + System.currentTimeMillis()).getAbsolutePath();
    }

    private int createAuthenticationProvider(String authenticationProviderName) throws Exception
    {
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(AuthenticationProvider.NAME, authenticationProviderName);
        attributes.put(AuthenticationProvider.TYPE, AnonymousAuthenticationManagerFactory.PROVIDER_TYPE);

        return getRestTestHelper().submitRequest("/rest/authenticationprovider/" + authenticationProviderName, "PUT", attributes);
    }

    private void assertAuthenticationProviderDoesNotExist(String authenticationProviderName) throws Exception
    {
        assertAuthenticationProviderExistence(authenticationProviderName, false);
    }

    private void assertAuthenticationProviderExists(String authenticationProviderName) throws Exception
    {
        assertAuthenticationProviderExistence(authenticationProviderName, true);
    }

    private void assertAuthenticationProviderExistence(String authenticationProviderName, boolean exists) throws Exception
    {
        String path = "/rest/authenticationprovider/" + authenticationProviderName;
        List<Map<String, Object>> providers = getRestTestHelper().getJsonAsList(path);
        assertEquals("Unexpected result", exists, !providers.isEmpty());
    }

    private int createKeyStore(String name, String certAlias) throws IOException, JsonGenerationException, JsonMappingException
    {
        Map<String, Object> keyStoreAttributes = new HashMap<String, Object>();
        keyStoreAttributes.put(KeyStore.NAME, name);
        keyStoreAttributes.put(KeyStore.PATH, TestSSLConstants.KEYSTORE);
        keyStoreAttributes.put(KeyStore.PASSWORD, TestSSLConstants.KEYSTORE_PASSWORD);
        keyStoreAttributes.put(KeyStore.CERTIFICATE_ALIAS, certAlias);

        return getRestTestHelper().submitRequest("/rest/keystore/" + name, "PUT", keyStoreAttributes);
    }

    private int createTrustStore(String name, boolean peersOnly) throws IOException, JsonGenerationException, JsonMappingException
    {
        Map<String, Object> trustStoreAttributes = new HashMap<String, Object>();
        trustStoreAttributes.put(TrustStore.NAME, name);
        trustStoreAttributes.put(TrustStore.PATH, TestSSLConstants.KEYSTORE);
        trustStoreAttributes.put(TrustStore.PASSWORD, TestSSLConstants.KEYSTORE_PASSWORD);
        trustStoreAttributes.put(TrustStore.PEERS_ONLY, peersOnly);

        return getRestTestHelper().submitRequest("/rest/truststore/" + name, "PUT", trustStoreAttributes);
    }
}
