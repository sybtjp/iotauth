/*
 * Copyright (c) 2016, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * IOTAUTH_COPYRIGHT_VERSION_1
 */

package org.iot.auth;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.iot.auth.config.AuthServerProperties;
import org.iot.auth.config.constants.C;
import org.iot.auth.db.AuthDB;
import org.iot.auth.db.CommunicationPolicy;
import org.iot.auth.db.DistributionKey;
import org.iot.auth.db.RegisteredEntity;
import org.iot.auth.db.SessionKey;
import org.iot.auth.db.TrustedAuth;
import org.iot.auth.server.CommunicationTargetType;
import org.iot.auth.server.EntityConnectionHandler;
import org.iot.auth.server.TrustedAuthConnectionHandler;
import org.iot.auth.util.ExceptionToString;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * A main class for Auth, a local authentication/authorization entity for locally
 * registered entities.
 * @author Hokeun Kim, Salomon Lee
 */
public class AuthServer {
    private boolean isRunning() {
        return isRunning;
    }

    private void setRunning(boolean running) {
        this.isRunning = running;
    }

    public AuthServer(AuthServerProperties properties) throws Exception {
        this.db = new AuthDB(properties.getAuthDatabaseDir());

        // TODO: replace this with password input
        String authKeyStorePassword = "asdf";

        db.initialize(authKeyStorePassword);
        logger.info("Finished initializing Auth DB.");

        authID =  properties.getAuthID();

        crypto = new AuthCrypto(properties.getEntityKeyStorePath(), authKeyStorePassword);

        entityPortTimeout = properties.getEntityPortTimeout();

        // suppress default logging by jetty
        org.eclipse.jetty.util.log.Log.setLog(new NoLogging());

        // TODO: get Port for this
        entityPortServerSocket = new ServerSocket(properties.getEntityPort());

        serverForTrustedAuths = initServerForTrustedAuths(properties, authKeyStorePassword);
        clientForTrustedAuths = initClientForTrustedAuths(properties, authKeyStorePassword);

        logger.info("Auth server information. Auth ID: {}, Entity Port: {}, Trusted auth Port: {}, Host name: {}",
                properties.getAuthID(), entityPortServerSocket.getLocalPort(),
                ((ServerConnector) serverForTrustedAuths.getConnectors()[0]).getPort(),
                properties.getHostName());

        setRunning(true);
    }

    /**
     * Getter for Auth's unique identifier
     * @return Auth's ID
     */
    public int getAuthID() {
        return authID;
    }

    /**
     * Getter for AuthCrypto object of Auth
     * @return Auth's AuthCrypto object
     */
    public AuthCrypto getCrypto() {
        return crypto;
    }

    /**
     * Main method of Auth server, which is executed at the very beginning
     * @param args Command line arguments
     * @throws Exception When any exception occurs
     */
    public static void main(String[] args) throws Exception {
        // parsing command line arguments
        Options options = new Options();

        Option properties = new Option("p", "properties", true, "properties file path");
        properties.setRequired(false);
        options.addOption(properties);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", options);

            System.exit(1);
            return;
        }
        String propertiesFilePath = cmd.getOptionValue("properties");
        if (propertiesFilePath == null) {
            logger.info("No properties file specified! (Use option -p to specify the properties file.)");
            System.exit(1);
            return;
        }
        logger.info("Properties file specified: {}", propertiesFilePath);

        C.PROPERTIES = new AuthServerProperties(propertiesFilePath);
        logger.info("Finished loading Auth Server properties.");

        AuthServer authServer = new AuthServer(C.PROPERTIES);
        authServer.begin();
    }

    /**
     * Starts Auth server
     * @throws Exception When any exception occurs
     */
    public void begin() throws Exception {
        EntityPortListener entityPortListener = new EntityPortListener(this);
        entityPortListener.start();

        AuthCommandLine authCommandLine = new AuthCommandLine(this);
        authCommandLine.start();

        clientForTrustedAuths.start();

        serverForTrustedAuths.start();
        serverForTrustedAuths.join();
    }

    /**
     * Send POST request to the trusted Auth, using HTTPS client, clientForTrustedAuths, this is why this method is
     * within AuthServer, not TrustedAuthConnectionHandler.
     * @param uri Host and port number of the trusted Auth.
     * @param keyVals Message to be sent to the trusted Auth, in JSON object format.
     * @return HTTP response from the trusted Auth
     * @throws TimeoutException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public ContentResponse performPostRequest(String uri, JSONObject keyVals) throws TimeoutException,
            ExecutionException, InterruptedException
    {
        org.eclipse.jetty.client.api.Request postRequest = clientForTrustedAuths.POST(uri);
        keyVals.forEach((k, v) -> {
            postRequest.param(k.toString(), v.toString());
        });
        return postRequest.send();
    }

    //////////////////////////////////////////////////
    ///
    /// Below are methods for exposing AuthDB operations, rather than exposing AuthDB object itself
    ///
    //////////////////////////////////////////////////
    /**
     * Method to view database information for all registered entities
     * @return String with information of all registered entities
     */
    public String registeredEntitiesToString() {
        return db.registeredEntitiesToString();
    }

    /**
     * Method to view database information for all communication policies
     * @return String with information of all communication policies
     */
    public String communicationPoliciesToString() {
        return db.communicationPoliciesToString();
    }

    /**
     * Method to view database information for all trsuted Auths
     * @return String with information of all trusted Auths
     */
    public String trustedAuthsToString() { return db.trustedAuthsToString(); }
    /**
     * Method for exposing an AuthDB operation, addSessionKeyOwner
     * @param keyID
     * @param newOwner
     * @return
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public boolean addSessionKeyOwner(long keyID, String newOwner) throws SQLException, ClassNotFoundException {
        return db.addSessionKeyOwner(keyID, newOwner);
    }

    /**
     * Method for exposing an AuthDB operation, getCommunicationPolicy
     * @param reqGroup
     * @param targetType
     * @param target
     * @return
     */
    public CommunicationPolicy getCommunicationPolicy(String reqGroup, CommunicationTargetType targetType, String target) {
        return db.getCommunicationPolicy(reqGroup, targetType, target);
    }

    /**
     * Method for exposing an AuthDB operation, updateDistributionKey
     * @param entityName
     * @param distributionKey
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public void updateDistributionKey(String entityName, DistributionKey distributionKey)
            throws SQLException, ClassNotFoundException {
        db.updateDistributionKey(entityName, distributionKey);
    }

    /**
     * Method for exposing an AuthDB operation, generateSessionKeys
     * @param owner
     * @param numKeys
     * @param communicationPolicy
     * @return
     * @throws IOException
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public List<SessionKey> generateSessionKeys(String owner, int numKeys, CommunicationPolicy communicationPolicy)
            throws IOException, SQLException, ClassNotFoundException {
        return db.generateSessionKeys(authID, owner, numKeys, communicationPolicy);
    }

    /**
     * Method for exposing an AuthDB operation, getSessionKeyByID
     * @param keyID
     * @return
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public SessionKey getSessionKeyByID(long keyID) throws SQLException, ClassNotFoundException {
        return db.getSessionKeyByID(keyID);
    }

    /**
     * Method for exposing an AuthDB operation, sessionKeysToString
     * @return
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public String sessionKeysToString() throws SQLException, ClassNotFoundException {
        return db.sessionKeysToString();
    }

    /**
     * Method for exposing an AuthDB operation, getTrustedAuthIDByCertificate
     * @return
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public int getTrustedAuthIDByCertificate(X509Certificate cert) {
        return db.getTrustedAuthIDByCertificate(cert);
    }

    /**
     * Method for exposing an AuthDB operation, getRegisteredEntity
     * @return
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public RegisteredEntity getRegisteredEntity(String entityName) {
        return db.getRegisteredEntity(entityName);
    }

    /**
     *  Method for exposing an AuthDB operation, getTrustedAuthInfo
     * @param authID
     * @return
     */
    public TrustedAuth getTrustedAuthInfo(int authID) {
        return db.getTrustedAuthInfo(authID);
    }

    /**
     * Method for exposing an AuthDB operation, cleanExpiredSessionKeys
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public void cleanExpiredSessionKeys() throws SQLException, ClassNotFoundException {
        db.cleanExpiredSessionKeys();
    }

    //////////////////////////////////////////////////
    ///
    /// Above are methods for exposing AuthDB operations, rather than exposing AuthDB object itself
    ///
    //////////////////////////////////////////////////

    /**
     * Initialize HTTPS server to which trusted Auths connect
     * @param properties Auth server's properties to get paths for key stores and certificates
     * @param authKeyStorePassword Password for Auth's key store that is used for communication with trusted Auths
     * @return HTTPS server object
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws KeyStoreException
     * @throws IOException
     */
    private Server initServerForTrustedAuths(AuthServerProperties properties, String authKeyStorePassword)
            throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException
    {
        TrustedAuthConnectionHandler trustedAuthConnectionHandler = new TrustedAuthConnectionHandler(this);

        Server serverForTrustedAuths = new Server();
        serverForTrustedAuths.setHandler(trustedAuthConnectionHandler);

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setTrustAll(false);
        sslContextFactory.setKeyStore(AuthCrypto.loadKeyStore(properties.getInternetKeyStorePath(), authKeyStorePassword));
        sslContextFactory.setKeyStorePassword(authKeyStorePassword);

        KeyStore serverTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        serverTrustStore.load(null, authKeyStorePassword.toCharArray());
        String[] trustedCACertPaths = properties.getTrustedCACertPaths();
        for (int i = 0; i < trustedCACertPaths.length; i++) {
            serverTrustStore.setCertificateEntry("" + i, AuthCrypto.loadCertificate(trustedCACertPaths[i]));
        }
        sslContextFactory.setTrustStore(serverTrustStore);
        sslContextFactory.setNeedClientAuth(true);

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setPersistentConnectionsEnabled(true);
        httpConfig.setSecureScheme("https");
        // time out with out keep alive messages?
        //httpConfig.setBlockingTimeout();

        httpConfig.addCustomizer(new SecureRequestCustomizer());
        //new SSL
        ServerConnector connector = new ServerConnector(serverForTrustedAuths,
                new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpConfig));

        connector.setPort(properties.getTrustedAuthPort());

        // Idle time out for keep alive connections
        // time out with out requests?
        connector.setIdleTimeout(properties.getTrustedAuthPortIdleTimeout());

        serverForTrustedAuths.setConnectors(new Connector[]{connector});

        return serverForTrustedAuths;
    }

    /**
     * Initialize HTTPS client for connecting to other trusted Auths and sending Auth session key requests
     * @param properties Auth server's properties to get paths for key stores and certificates
     * @param authKeyStorePassword Password for Auth's key store that is used for communication with trusted Auths
     * @return HTTPS client object
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws KeyStoreException
     * @throws IOException
     */
    private HttpClient initClientForTrustedAuths(AuthServerProperties properties, String authKeyStorePassword)
            throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException
    {
        SslContextFactory sslContextFactory = new SslContextFactory();

        sslContextFactory.setTrustAll(false);
        sslContextFactory.setNeedClientAuth(true);
        sslContextFactory.setKeyStore(AuthCrypto.loadKeyStore(properties.getInternetKeyStorePath(), authKeyStorePassword));
        sslContextFactory.setKeyStorePassword(authKeyStorePassword);

        KeyStore clientTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        clientTrustStore.load(null, authKeyStorePassword.toCharArray());
        String[] trustedCACertPaths = properties.getTrustedCACertPaths();
        for (int i = 0; i < trustedCACertPaths.length; i++) {
            clientTrustStore.setCertificateEntry("" + i, AuthCrypto.loadCertificate(trustedCACertPaths[i]));
        }

        sslContextFactory.setTrustStore(clientTrustStore);

        sslContextFactory.setKeyManagerPassword(authKeyStorePassword);
        sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        try {
            sslContextFactory.start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        SSLEngine sslEngine = null;
        try {
            sslEngine = SSLContext.getDefault().createSSLEngine();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        SSLParameters sslParams = new SSLParameters();
        List<SNIServerName> list = new ArrayList<>();
        list.add(new SNIHostName("localhost"));
        sslParams.setServerNames(list);
        sslEngine.setSSLParameters(sslParams);

        sslContextFactory.customize(sslEngine);

        HttpClient clientForTrustedAuths = new HttpClient(sslContextFactory);

        return clientForTrustedAuths;
    }

    /**
     * Class for a thread that listens to requests coming from entities, and creates another thread for processing
     * the request from an entity, which is entityConnectionHandler
     */
    private class EntityPortListener extends Thread {
        public EntityPortListener(AuthServer server) {
            this.server = server;
        }
        public void run() {

            while(isRunning()) {
                try {
                    while (isRunning) {
                        Socket entitySocket = entityPortServerSocket.accept();
                        logger.info("An entity connected from: {} ", entitySocket.getRemoteSocketAddress());

                        EntityConnectionHandler entityConnectionHandler =
                                new EntityConnectionHandler(server, entitySocket, entityPortTimeout);
                        entityConnectionHandler.start();
                    }
                } catch (IOException e) {
                    logger.error("IOException {}", ExceptionToString.convertExceptionToStackTrace(e));
                }
            }
        }
        private AuthServer server;
    }

    /**
     * Class for suppressing logging by jetty
     */
    private class NoLogging implements org.eclipse.jetty.util.log.Logger {
        @Override public String getName() { return "no"; }
        @Override public void warn(String msg, Object... args) { }
        @Override public void warn(Throwable thrown) { }
        @Override public void warn(String msg, Throwable thrown) { }
        @Override public void info(String msg, Object... args) { }
        @Override public void info(Throwable thrown) { }
        @Override public void info(String msg, Throwable thrown) { }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void setDebugEnabled(boolean enabled) { }
        @Override public void debug(String msg, long value) { }
        @Override public void debug(String msg, Object... args) { }
        @Override public void debug(Throwable thrown) { }
        @Override public void debug(String msg, Throwable thrown) { }
        @Override public org.eclipse.jetty.util.log.Logger getLogger(String name) { return this; }
        @Override public void ignore(Throwable ignored) { }
    }

    private static final Logger logger = LoggerFactory.getLogger(AuthServer.class);

    private int authID;
    private long entityPortTimeout;

    private ServerSocket entityPortServerSocket;

    private boolean isRunning;
    private AuthDB db;
    private AuthCrypto crypto;

    private Server serverForTrustedAuths;
    private HttpClient clientForTrustedAuths;
}
