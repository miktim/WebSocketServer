/*
 * WebSocket. MIT (c) 2020-2025 miktim@mail.ru
 *
 * Creates and starts server/connection threads.
 *
 * Release notes:
 * - Java SE 6+, Android compatible;
 * - RFC-6455: https://tools.ietf.org/html/rfc6455 ;
 * - supported WebSocket version: 13;
 * - WebSocket extensions not supported;
 * - supports cleartext/TLS connections;
 * - stream-based messaging.
 *
 * Created: 2020-06-06
 */
package org.miktim.websocket;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class WebSocket {

    public static final String VERSION = "4.2.0";
    private InetAddress bindAddress = null;
    private final List<WsConnection> connections = Collections.synchronizedList(new ArrayList<WsConnection>());
    private final List<WsServer> servers = Collections.synchronizedList(new ArrayList<WsServer>());
    private File keyStoreFile = null;
    private String keyStorePassword = null;

    public WebSocket() throws NoSuchAlgorithmException {
        MessageDigest.getInstance("SHA-1"); // check algorithm exists
    }

    public WebSocket(InetAddress bindAddr) throws SocketException {
        super();
        if (NetworkInterface.getByInetAddress(bindAddr) == null) {
            throw new BindException("Not interface");
        }
        bindAddress = bindAddr;
    }

    public static void setTrustStore(String jksFile, String passphrase) {
        System.setProperty("javax.net.ssl.trustStore", jksFile);
        System.setProperty("javax.net.ssl.trustStorePassword", passphrase);
    }

    public static void setKeyStore(String jksFile, String passphrase) {
        System.setProperty("javax.net.ssl.keyStore", jksFile);
        System.setProperty("javax.net.ssl.keyStorePassword", passphrase);
    }

// convert host name to International Domain Names (IDN) format and create URI
    public static URI idnURI(String uri) throws URISyntaxException {
// Supported uri format: [scheme:][//[user-info@]host][:port][/path][?query][#fragment]
// https://stackoverflow.com/questions/9607903/get-domain-name-from-given-url
        Pattern pattern = Pattern.compile(
                "^(([^:/?#]+):)?(//(([^@]+)@)?([^:/?#]*))?(.*)(\\?([^#]*))?(#(.*))?$");
        Matcher matcher = pattern.matcher(uri);
        matcher.find();
        String host = null;
        if (matcher.groupCount() > 5) {
            host = matcher.group(6); // extract host from uri
        }
        if (host != null) {
            uri = uri.replace(host, java.net.IDN.toASCII(host));
        }
        return new URI(uri);
    }

    public void setKeyFile(File storeFile, String storePassword) {
        keyStoreFile = storeFile;
        keyStorePassword = storePassword;
    }

    public void resetKeyFile() {
        keyStoreFile = null;
        keyStorePassword = null;
    }

    public InetAddress getBindAddress() {
        return bindAddress;
    }

    public WsConnection[] listConnections() {
        return connections.toArray(new WsConnection[0]);
    }

    public WsServer[] listServers() {
        return servers.toArray(new WsServer[0]);
    }

    public void closeAll(String closeReason) {
// close WebSocket servers/connections 
        for (WsServer server : listServers()) {
            server.close(closeReason);
        }
        for (WsConnection conn : listConnections()) {
            conn.close(WsStatus.GOING_AWAY, closeReason);
        }
    }

    public void closeAll() {
        closeAll("");
    }

    public WsServer Server(int port, WsConnection.Handler handler, WsParameters wsp)
            throws IOException, GeneralSecurityException {
        return createServer(port, handler, false, wsp);
    }

    public WsServer SecureServer(int port, WsConnection.Handler handler, WsParameters wsp)
            throws IOException, GeneralSecurityException {
        return createServer(port, handler, true, wsp);
    }

    synchronized WsServer createServer(int port, WsConnection.Handler handler, boolean isSecure, WsParameters wsp)
            throws IOException, GeneralSecurityException {
        if (handler == null || wsp == null) {
            throw new NullPointerException();
        }
        wsp = wsp.deepClone();

        ServerSocket serverSocket;
        if (isSecure) {
            ServerSocketFactory serverSocketFactory;
            if (this.keyStoreFile != null) {
                serverSocketFactory = getSSLContext(false)
                        .getServerSocketFactory();
            } else {
                serverSocketFactory = SSLServerSocketFactory.getDefault();
            }
            serverSocket = serverSocketFactory
                    .createServerSocket(port, wsp.backlog, bindAddress);

            SSLParameters sslp = wsp.getSSLParameters();
            if (sslp != null) {
                ((SSLServerSocket) serverSocket).setNeedClientAuth(sslp.getNeedClientAuth());
                ((SSLServerSocket) serverSocket).setEnabledProtocols(sslp.getProtocols());
                ((SSLServerSocket) serverSocket).setWantClientAuth(sslp.getWantClientAuth());
                ((SSLServerSocket) serverSocket).setEnabledCipherSuites(sslp.getCipherSuites());
// TODO: downgrade Android API 24 to API 16

//            ((SSLServerSocket) serverSocket).setSSLParameters(wsp.sslParameters);
            }
        } else {
            serverSocket = new ServerSocket(port, wsp.backlog, bindAddress);
        }

        serverSocket.setSoTimeout(0);
        WsServer server
                = new WsServer(serverSocket, handler, isSecure, wsp);
        server.servers = servers;
        return server;
    }

// https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/samples/sockets/server/ClassFileServer.java
    synchronized private SSLContext getSSLContext(boolean isClient)
            throws IOException, GeneralSecurityException {
//            throws NoSuchAlgorithmException, KeyStoreException,
//            FileNotFoundException, IOException, CertificateException,
//            UnrecoverableKeyException {
        SSLContext ctx;
        KeyManagerFactory kmf;
        KeyStore ks;// = KeyStore.getInstance(KeyStore.getDefaultType());

        String ksPassphrase = this.keyStorePassword;
        File ksFile = this.keyStoreFile;
        char[] passphrase = ksPassphrase.toCharArray();

        ctx = SSLContext.getInstance("TLS");
        kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()); // java:"SunX509", android:"PKIX"
        ks = KeyStore.getInstance(KeyStore.getDefaultType()); // "JKS", "BKS"
        FileInputStream ksFis = new FileInputStream(ksFile);
        ks.load(ksFis, passphrase); // store password
        ksFis.close();
        kmf.init(ks, passphrase); // key password

        if (isClient) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()); // "PKIX", ""
            tmf.init(ks);

            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
        } else {
            ctx.init(kmf.getKeyManagers(), null, null);
        }
        return ctx;
    }

    synchronized public WsConnection connect(String uri,
            WsConnection.Handler handler, WsParameters wsp)
            throws URISyntaxException, IOException, GeneralSecurityException {
        if (uri == null || handler == null || wsp == null) {
            throw new NullPointerException();
        }
        wsp = wsp.deepClone();
        URI requestURI = idnURI(uri);
        String scheme = requestURI.getScheme();
        String host = requestURI.getHost();
        if (host == null || scheme == null) {
            throw new URISyntaxException(uri, "Scheme and host required");
        }
        if (!(scheme.equals("ws") || scheme.equals("wss"))) {
            throw new URISyntaxException(uri, "Unsupported scheme");
        }

        Socket socket;
        boolean isSecure = scheme.equals("wss");
        SSLSocketFactory factory;

        if (isSecure) {
            if (this.keyStoreFile != null) {
                factory = getSSLContext(true).getSocketFactory();
            } else {
                factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            }
            socket = (SSLSocket) factory.createSocket();
            if (wsp.sslParameters != null) {
                ((SSLSocket) socket).setSSLParameters(wsp.sslParameters);
            }
        } else {
            socket = new Socket();
        }
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindAddress, 0));
        int port = requestURI.getPort();
        if (port < 0) {
            port = isSecure ? 443 : 80;
        }
        socket.connect(
                new InetSocketAddress(requestURI.getHost(), port), wsp.handshakeSoTimeout);

        WsConnection conn = new WsConnection(socket, handler, requestURI, wsp);
        conn.connections = this.connections;
        conn.start();
        return conn;
    }

}
