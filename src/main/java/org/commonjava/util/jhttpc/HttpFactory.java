/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.jhttpc;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.ssl.PrivateKeyStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.commonjava.util.jhttpc.INTERNAL.conn.ConnectionManagerCache;
import org.commonjava.util.jhttpc.INTERNAL.conn.ConnectionManagerTracker;
import org.commonjava.util.jhttpc.INTERNAL.conn.SiteConnectionConfig;
import org.commonjava.util.jhttpc.INTERNAL.conn.TrackedHttpClient;
import org.commonjava.util.jhttpc.INTERNAL.util.CertEnumerator;
import org.commonjava.util.jhttpc.INTERNAL.util.MonolithicKeyStrategy;
import org.commonjava.util.jhttpc.INTERNAL.util.SSLUtils;
import org.commonjava.util.jhttpc.auth.PasswordKey;
import org.commonjava.util.jhttpc.auth.PasswordManager;
import org.commonjava.util.jhttpc.auth.PasswordType;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.commonjava.util.jhttpc.model.SiteTrustType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;

public class HttpFactory
        implements Closeable
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final PasswordManager passwords;

    private final ConnectionManagerCache connectionCache;

    public HttpFactory( final PasswordManager passwords )
    {
        this.passwords = passwords;
        this.connectionCache = new ConnectionManagerCache();
    }

    public PasswordManager getPasswordManager()
    {
        return passwords;
    }

    public CloseableHttpClient createClient()
            throws JHttpCException
    {
        return createClient( null );
    }

    public CloseableHttpClient createClient( final SiteConfig location )
            throws JHttpCException
    {
        CloseableHttpClient client;
        if ( location != null )
        {
            final HttpClientBuilder builder = HttpClients.custom();

            logger.debug( "Using site config: {} for advanced client options", location );
            SiteConnectionConfig connConfig = new SiteConnectionConfig(location);

            final SSLConnectionSocketFactory sslFac = createSSLSocketFactory( location );
            if ( sslFac != null )
            {
                //                HostnameVerifier verifier = new SSLHostnameVerifierImpl( );
                //                builder.setSSLHostnameVerifier( verifier );
                builder.setSSLSocketFactory( sslFac );
                connConfig.withSSLConnectionSocketFactory( sslFac );
            }

            ConnectionManagerTracker managerWrapper = connectionCache.getTrackerFor( connConfig );
            builder.setConnectionManager( managerWrapper.acquire() );

            if ( location.getProxyHost() != null )
            {
                final HttpRoutePlanner planner = new DefaultProxyRoutePlanner(
                        new HttpHost( location.getProxyHost(), getProxyPort( location ) ) );
                builder.setRoutePlanner( planner );
            }

            final int timeout = 1000 * location.getRequestTimeoutSeconds();
            builder.setDefaultRequestConfig( RequestConfig.custom()
                                                          .setConnectionRequestTimeout( timeout )
                                                          .setSocketTimeout( timeout )
                                                          .setConnectTimeout( timeout )
                                                          .build() );

            client = new TrackedHttpClient( builder.build(), managerWrapper );
//            client = builder.build();
        }
        else
        {
            client = HttpClients.createDefault();
        }

        return client;
    }

    private int getProxyPort( final SiteConfig location )
    {
        int port = location.getProxyPort();
        if ( port < 1 )
        {
            port = -1;
        }

        return port;
    }

    public HttpClientContext createContext()
            throws JHttpCException
    {
        return createContext( null );
    }

    public HttpClientContext createContext( final SiteConfig location )
            throws JHttpCException
    {
        final HttpClientContext ctx = HttpClientContext.create();

        if ( location != null )
        {
            final CredentialsProvider creds = new BasicCredentialsProvider();
            final AuthScope as;
            try
            {
                as = new AuthScope( location.getHost(), location.getPort() );
            }
            catch ( MalformedURLException e )
            {
                throw new JHttpCException( "Failed to parse site URL for host and port: %s (site id: %s). Reason: %s",
                                           e, location.getUri(), location.getId(), e.getMessage() );
            }

            if ( location.getUser() != null )
            {
                final String password = passwords.lookup( new PasswordKey( location, PasswordType.USER ) );
                creds.setCredentials( as, new UsernamePasswordCredentials( location.getUser(), password ) );
            }

            if ( location.getProxyHost() != null && location.getProxyUser() != null )
            {
                final String password = passwords.lookup( new PasswordKey( location, PasswordType.PROXY ) );
                creds.setCredentials( new AuthScope( location.getProxyHost(), getProxyPort( location ) ),
                                      new UsernamePasswordCredentials( location.getProxyUser(), password ) );
            }

            ctx.setCredentialsProvider( creds );
        }

        return ctx;
    }

    private SSLConnectionSocketFactory createSSLSocketFactory( final SiteConfig location )
            throws JHttpCException
    {
        KeyStore ks = null;
        KeyStore ts = null;

        final String kcPem = location.getKeyCertPem();

        final String kcPass = passwords.lookup( new PasswordKey( location, PasswordType.KEY ) );
        if ( kcPem != null )
        {
            logger.debug( "Adding client key/certificate from: {}", location );
            if ( kcPass == null || kcPass.length() < 1 )
            {
                logger.error( "Invalid configuration. Location: {} cannot have an empty key password!",
                              location.getUri() );
                throw new JHttpCException( "Location: " + location.getUri() + " is misconfigured! Key password cannot be empty." );
            }

            try
            {
                logger.debug( "Reading Client SSL key from:\n\n{}\n\n", kcPem );
                ks = SSLUtils.readKeyAndCert( kcPem, kcPass );

                logger.debug( "Keystore contains the following certificates: {}", new CertEnumerator( ks, kcPass ) );
            }
            catch ( final CertificateException e )
            {
                logger.error( String.format(
                        "Invalid configuration. Location: %s has an invalid client certificate! Error: %s",
                        location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final KeyStoreException e )
            {
                logger.error( String.format(
                        "Invalid configuration. Cannot initialize keystore for repository: %s. Error: %s",
                        location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final NoSuchAlgorithmException e )
            {
                logger.error( String.format(
                        "Invalid configuration. Cannot initialize keystore for repository: %s. Error: %s",
                        location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final InvalidKeySpecException e )
            {
                logger.error( String.format( "Invalid configuration. Invalid client key for repository: %s. Error: %s",
                                             location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( IOException e )
            {
                throw new JHttpCException( "Failed to read client SSL key/certificate from: %s. Reason: %s", e,
                        location, e.getMessage() );
            }
            catch ( JHttpCException e )
            {
                throw new JHttpCException( "Failed to read client SSL key/certificate from: %s. Reason: %s", e,
                        location, e.getMessage() );
            }
        }
        else
        {
            logger.debug( "No client key/certificate found" );
        }

        final String sPem = location.getServerCertPem();

        //        logger.debug( "Server certificate PEM:\n{}", sPem );
        if ( sPem != null )
        {
            logger.debug( "Adding server certificate(s) from: {}", location );
            try
            {
                logger.debug( "Reading Server SSL cert from:\n\n{}\n\n", sPem );
                ts = SSLUtils.readCerts( sPem, location.getHost() );

                //                logger.debug( "Trust store contains the following certificates:\n{}", new CertEnumerator( ts ) );
            }
            catch ( final CertificateException e )
            {
                logger.error( String.format(
                        "Invalid configuration. Location: %s has an invalid server certificate! Error: %s",
                        location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final KeyStoreException e )
            {
                logger.error( String.format(
                        "Invalid configuration. Cannot initialize keystore for repository: %s. Error: %s",
                        location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final NoSuchAlgorithmException e )
            {
                logger.error( String.format(
                        "Invalid configuration. Cannot initialize keystore for repository: %s. Error: %s",
                        location.getUri(), e.getMessage() ), e );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( IOException e )
            {
                throw new JHttpCException( "Failed to read server SSL certificate(s) (or couldn't parse server hostname) from: %s. Reason: %s", e,
                                           location, e.getMessage() );
            }
        }
        else
        {
            logger.debug( "No server certificates found" );
        }

        if ( ks != null || ts != null )
        {
            logger.debug( "Setting up SSL context." );
            try
            {
                SSLContextBuilder sslBuilder = SSLContexts.custom().useProtocol( SSLConnectionSocketFactory.TLS );
                if ( ks != null )
                {
                    logger.debug( "Loading key material for SSL context..." );
                    PrivateKeyStrategy pkStrategy = new MonolithicKeyStrategy();
                    sslBuilder.loadKeyMaterial( ks, kcPass.toCharArray(), pkStrategy );
                }

                if ( ts != null )
                {
                    logger.debug( "Loading trust material for SSL context..." );

                    SiteTrustType trustType = location.getTrustType();
                    if ( trustType == null )
                    {
                        trustType = SiteTrustType.DEFAULT;
                    }

                    sslBuilder.loadTrustMaterial( ts, trustType.getTrustStrategy() );
                }

                SSLContext ctx = sslBuilder.build();

                return new SSLConnectionSocketFactory( ctx, new DefaultHostnameVerifier() );
            }
            catch ( final KeyManagementException e )
            {
                logger.error(
                        "Invalid configuration. Cannot initialize SSL socket factory for repository: {}. Error: {}", e,
                        location.getUri(), e.getMessage() );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final UnrecoverableKeyException e )
            {
                logger.error(
                        "Invalid configuration. Cannot initialize SSL socket factory for repository: {}. Error: {}", e,
                        location.getUri(), e.getMessage() );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final NoSuchAlgorithmException e )
            {
                logger.error(
                        "Invalid configuration. Cannot initialize SSL socket factory for repository: {}. Error: {}", e,
                        location.getUri(), e.getMessage() );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
            catch ( final KeyStoreException e )
            {
                logger.error(
                        "Invalid configuration. Cannot initialize SSL socket factory for repository: {}. Error: {}", e,
                        location.getUri(), e.getMessage() );
                throw new JHttpCException( "Failed to initialize SSL connection for repository: " + location.getUri() );
            }
        }
        else
        {
            logger.debug( "No SSL configuration present; no SSL context created." );
        }

        return null;
    }

    public void close()
            throws IOException
    {
        //        connectionManager.reallyShutdown();
    }
}
