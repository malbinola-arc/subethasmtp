package org.subethamail.smtp.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.concurrent.GuardedBy;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.Version;
import org.subethamail.smtp.helper.SimpleMessageListener;
import org.subethamail.smtp.helper.SimpleMessageListenerAdapter;

import com.github.davidmoten.guavamini.Preconditions;

/**
 * Main SMTPServer class. Construct this object, set the hostName, port, and
 * bind address if you wish to override the defaults, and call start().
 *
 * This class starts a ServerSocket and creates a new instance of the
 * ConnectionHandler class when a new connection comes in. The ConnectionHandler
 * then parses the incoming SMTP stream and hands off the processing to the
 * CommandHandler which will execute the appropriate SMTP command class.
 *
 * To use this class, construct a server with your implementation of the
 * MessageHandlerFactory. This provides low-level callbacks at various phases of
 * the SMTP exchange. For a higher-level but more limited interface, you can
 * pass in a org.subethamail.smtp.helper.SimpleMessageListenerAdapter.
 *
 * By default, no authentication methods are offered. To use authentication, set
 * an {@link AuthenticationHandlerFactory}.
 *
 * @author Jon Stevens
 * @author Ian McFarland &lt;ian@neo.com&gt;
 * @author Jeff Schnitzer
 */
public final class SMTPServer implements SSLSocketCreator {
    private final static Logger log = LoggerFactory.getLogger(SMTPServer.class);

    /** Hostname used if we can't find one */
    private final static String UNKNOWN_HOSTNAME = "localhost";

    private final Optional<InetAddress> bindAddress; // default to all
                                                     // interfaces
    private final int port; // default to 25
    private final String hostName; // defaults to a lookup of the local address
    private final int backlog;
    private final String softwareName;

    private final MessageHandlerFactory messageHandlerFactory;
    private final Optional<AuthenticationHandlerFactory> authenticationHandlerFactory;
    private final ExecutorService executorService;

    private final CommandHandler commandHandler;

    /** If true, TLS is enabled */
    private final boolean enableTLS;

    /** If true, TLS is not announced; ignored if enableTLS=false */
    private final boolean hideTLS;

    /** If true, a TLS handshake is required; ignored if enableTLS=false */
    private final boolean requireTLS;

    /**
     * If true, this server will accept no mail until auth succeeded; ignored if
     * no AuthenticationHandlerFactory has been set
     */
    private final boolean requireAuth;

    /** If true, no Received headers will be inserted */
    private final boolean disableReceivedHeaders;

    /**
     * set a hard limit on the maximum number of connections this server will
     * accept once we reach this limit, the server will gracefully reject new
     * connections. Default is 1000.
     */
    private final int maxConnections;

    /**
     * The timeout for waiting for data on a connection is one minute: 1000 * 60
     * * 1
     */
    private final int connectionTimeout;

    /**
     * The maximal number of recipients that this server accepts per message
     * delivery request.
     */
    private final int maxRecipients;

    /**
     * The maximum size of a message that the server will accept. This value is
     * advertised during the EHLO phase if it is larger than 0. If the message
     * size specified by the client during the MAIL phase, the message will be
     * rejected at that time. (RFC 1870) Default is 0. Note this doesn't
     * actually enforce any limits on the message being read; you must do that
     * yourself when reading data.
     */
    private final int maxMessageSize;

    private final SessionIdFactory sessionIdFactory;

    // mutable state

    /** The thread listening on the server socket. */
    @GuardedBy("this")
    private ServerThread serverThread;

    /**
     * True if this SMTPServer was started. It remains true even if the
     * SMTPServer has been stopped since. It is used to prevent restarting this
     * object. Even if it was shutdown properly, it cannot be restarted, because
     * the contained thread pool object itself cannot be restarted.
     **/
    @GuardedBy("this")
    private boolean started = false;

    private volatile int allocatedPort;

    private final SSLSocketCreator sslSocketCreator;

    public static final class Builder {
        private String hostName;
        private Optional<InetAddress> bindAddress = Optional.empty(); // default
                                                                      // to all
                                                                      // interfaces
        private int port = 25; // default to 25
        private int backlog = 50;
        private String softwareName = "SubEthaSMTP " + Version.getSpecification();

        private MessageHandlerFactory messageHandlerFactory;
        private Optional<AuthenticationHandlerFactory> authenticationHandlerFactory = Optional
                .empty();
        private Optional<ExecutorService> executorService = Optional.empty();

        /** If true, TLS is enabled */
        private boolean enableTLS = false;
        /** If true, TLS is not announced; ignored if enableTLS=false */
        private boolean hideTLS = false;
        /** If true, a TLS handshake is required; ignored if enableTLS=false */
        private boolean requireTLS = false;
        /**
         * If true, this server will accept no mail until auth succeeded;
         * ignored if no AuthenticationHandlerFactory has been set
         */
        private boolean requireAuth = false;

        /** If true, no Received headers will be inserted */
        private boolean disableReceivedHeaders = false;

        /**
         * set a hard limit on the maximum number of connections this server
         * will accept once we reach this limit, the server will gracefully
         * reject new connections. Default is 1000.
         */
        private int maxConnections = 1000;

        /**
         * The timeout for waiting for data on a connection is one minute: 1000
         * * 60 * 1
         */
        private int connectionTimeout = 1000 * 60 * 1;

        /**
         * The maximal number of recipients that this server accepts per message
         * delivery request.
         */
        private int maxRecipients = 1000;

        /**
         * The maximum size of a message that the server will accept. This value
         * is advertised during the EHLO phase if it is larger than 0. If the
         * message size specified by the client during the MAIL phase, the
         * message will be rejected at that time. (RFC 1870) Default is 0. Note
         * this doesn't actually enforce any limits on the message being read;
         * you must do that yourself when reading data.
         */
        private int maxMessageSize = 0;

        private SessionIdFactory sessionIdFactory = new TimeBasedSessionIdFactory();

        private SSLSocketCreator sslSocketCreator = SSL_SOCKET_CREATOR_DEFAULT;

        public Builder bindAddress(InetAddress bindAddress) {
            Preconditions.checkNotNull(bindAddress, "bindAddress cannot be null");
            this.bindAddress = Optional.of(bindAddress);
            return this;
        }

        public Builder bindAddress(Optional<InetAddress> bindAddress) {
            Preconditions.checkNotNull(bindAddress, "bindAddress cannot be null");
            this.bindAddress = bindAddress;
            return this;
        }

        public Builder hostName(String hostName) {
            Preconditions.checkNotNull(hostName);
            this.hostName = hostName;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Sets the Socket backlog (?).
         *
         * @param backlogSize
         *            The backlog argument must be a positive value greater than
         *            0. If the value passed if equal or less than 0, then the
         *            default value will be assumed.
         *
         * @return this
         */
        public Builder backlog(int backlogSize) {
            Preconditions.checkArgument(backlogSize >= 0);
            this.backlog = backlogSize;
            return this;
        }

        public Builder softwareName(String name) {
            Preconditions.checkNotNull(name);
            this.softwareName = name;
            return this;
        }

        public Builder messageHandlerFactory(MessageHandlerFactory factory) {
            Preconditions.checkNotNull(factory);
            this.messageHandlerFactory = factory;
            return this;
        }

        public Builder simpleMessageListener(SimpleMessageListener listener) {
            this.messageHandlerFactory = new SimpleMessageListenerAdapter(listener);
            return this;
        }

        /**
         * Sets authenticationHandlerFactory.
         * 
         * @param factory
         *            the {@link AuthenticationHandlerFactory} which performs
         *            authentication in the SMTP AUTH command. If empty,
         *            authentication is not supported. Note that setting an
         *            authentication handler does not enforce authentication, it
         *            only makes authentication possible. Enforcing
         *            authentication is the responsibility of the client
         *            application, which usually enforces it only selectively.
         *            Use {@link Session#isAuthenticated} to check whether the
         *            client was authenticated in the session.
         * @return this
         */
        public Builder authenticationHandlerFactory(AuthenticationHandlerFactory factory) {
            Preconditions.checkNotNull(factory);
            this.authenticationHandlerFactory = Optional.of(factory);
            return this;
        }

        /**
         * Sets the executor service that will handle client connections.
         * 
         * @param executor
         *            the ExecutorService that will handle client connections,
         *            one task per connection. The SMTPServer will shut down
         *            this ExecutorService when the SMTPServer itself stops. If
         *            not specified, a default one is created by
         *            {@link Executors#newCachedThreadPool()}.
         * @return this
         */
        public Builder executorService(ExecutorService executor) {
            Preconditions.checkNotNull(executor);
            this.executorService = Optional.of(executor);
            return this;
        }

        public Builder enableTLS(boolean value) {
            this.enableTLS = value;
            return this;
        }

        /**
         * If set to true, TLS will be supported.
         * <p>
         * The minimal JSSE configuration necessary for a working TLS support on
         * Oracle JRE 6:
         * <ul>
         * <li>javax.net.ssl.keyStore system property must refer to a file
         * containing a JKS keystore with the private key.
         * <li>javax.net.ssl.keyStorePassword system property must specify the
         * keystore password.
         * </ul>
         * <p>
         * Up to SubEthaSMTP 3.1.5 the default was true, i.e. TLS was enabled.
         * 
         * @see <a href=
         *      "http://blog.jteam.nl/2009/11/10/securing-connections-with-tls/">
         *      Securing Connections with TLS</a>
         */
        public Builder enableTLS() {
            return enableTLS(true);
        }

        /**
         * If set to true, TLS will not be advertised in the EHLO string.
         * Default is false; true implied when disableTLS=true.
         */
        public Builder hideTLS(boolean value) {
            this.hideTLS = value;
            return this;
        }

        /**
         * If set to true, TLS will not be advertised in the EHLO string.
         * Default is false; true implied when disableTLS=true.
         */
        public Builder hideTLS() {
            return hideTLS(true);
        }

        /**
         * @param requireTLS
         *            true to require a TLS handshake, false to allow operation
         *            with or without TLS. Default is false; ignored when
         *            disableTLS=true.
         */
        public Builder requireTLS(boolean value) {
            this.requireTLS = value;
            return this;
        }

        public Builder requireTLS() {
            return requireTLS(true);
        }

        /**
         * Sets whether authentication is required. If set to true then no mail
         * will be accepted till authentication succeeds.
         * 
         * @param requireAuth
         *            true for mandatory smtp authentication, i.e. no mail mail
         *            be accepted until authentication succeeds. Don't forget to
         *            set {@code authenticationHandlerFactory} to allow client
         *            authentication. Defaults to false.
         */
        public Builder requireAuth(boolean value) {
            this.requireAuth = value;
            return this;
        }

        public Builder requireAuth() {
            return requireAuth(true);
        }

        public Builder insertReceivedHeaders(boolean value) {
            this.disableReceivedHeaders = !value;
            return this;
        }

        public Builder insertReceivedHeaders() {
            this.disableReceivedHeaders = false;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder connectionTimeoutMs(int connectionTimeoutMs) {
            this.connectionTimeout = connectionTimeoutMs;
            return this;
        }

        /**
         * Sets the maximum number of recipients per message delivery request.
         * 
         * @param maxRecipients
         *            The maximum number of recipients that this server accepts
         *            per message delivery request.
         * @return this
         */
        public Builder maxRecipients(int maxRecipients) {
            this.maxRecipients = maxRecipients;
            return this;
        }

        /**
         * Sets the maximum messages size (does not enforce though!).
         * 
         * @param maxMessageSize
         *            The maximum size of a message that the server will accept.
         *            This value is advertised during the EHLO phase if it is
         *            larger than 0. If the message size specified by the client
         *            during the MAIL phase, the message will be rejected at
         *            that time. (RFC 1870) Default is 0. Note this doesn't
         *            actually enforce any limits on the message being read; you
         *            must do that yourself when reading data.
         * @return this
         */
        public Builder maxMessageSize(int maxMessageSize) {
            this.maxMessageSize = maxMessageSize;
            return this;
        }

        /**
         * Sets the {@link SessionIdFactory} which will allocate a unique
         * identifier for each mail sessions. If not set, a reasonable default
         * will be used.
         */
        public Builder sessionIdFactory(SessionIdFactory factory) {
            this.sessionIdFactory = factory;
            return this;
        }

        public Builder sslSocketCreator(SSLSocketCreator creator) {
            this.sslSocketCreator = creator;
            return this;
        }

        public SMTPServer build() {
            return new SMTPServer(hostName, bindAddress, port, backlog, softwareName,
                    messageHandlerFactory, authenticationHandlerFactory, executorService, enableTLS,
                    hideTLS, requireTLS, requireAuth, disableReceivedHeaders, maxConnections,
                    connectionTimeout, maxRecipients, maxMessageSize, sessionIdFactory,
                    sslSocketCreator);
        }

    }

    private SMTPServer(String hostName, Optional<InetAddress> bindAddress, int port, int backlog,
            String softwareName, MessageHandlerFactory messageHandlerFactory,
            Optional<AuthenticationHandlerFactory> authenticationHandlerFactory,
            Optional<ExecutorService> executorService, boolean enableTLS, boolean hideTLS,
            boolean requireTLS, boolean requireAuth, boolean disableReceivedHeaders,
            int maxConnections, int connectionTimeout, int maxRecipients, int maxMessageSize,
            SessionIdFactory sessionIdFactory, SSLSocketCreator sslSocketCreator) {
        Preconditions.checkNotNull(messageHandlerFactory);
        Preconditions.checkArgument(!requireAuth || authenticationHandlerFactory != null,
                "if requireAuth is set to true then you must specify an authenticationHandlerFactory");
        this.bindAddress = bindAddress;
        this.port = port;
        this.backlog = backlog;
        this.softwareName = softwareName;
        this.messageHandlerFactory = messageHandlerFactory;
        this.authenticationHandlerFactory = authenticationHandlerFactory;
        this.enableTLS = enableTLS;
        this.hideTLS = hideTLS;
        this.requireTLS = requireTLS;
        this.requireAuth = requireAuth;
        this.disableReceivedHeaders = disableReceivedHeaders;
        this.maxConnections = maxConnections;
        this.connectionTimeout = connectionTimeout;
        this.maxRecipients = maxRecipients;
        this.maxMessageSize = maxMessageSize;
        this.sessionIdFactory = sessionIdFactory;
        this.commandHandler = new CommandHandler();
        this.sslSocketCreator = sslSocketCreator;

        if (executorService.isPresent()) {
            this.executorService = executorService.get();
        } else {
            this.executorService = Executors.newCachedThreadPool();
        }
        if (hostName == null) {
            String s;
            try {
                s = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                s = UNKNOWN_HOSTNAME;
            }
            this.hostName = s;
        } else {
            this.hostName = hostName;
        }
        this.allocatedPort = port;
    }

    private static final SSLSocketCreator SSL_SOCKET_CREATOR_DEFAULT = new SSLSocketCreator() {

        @Override
        public SSLSocket createSSLSocket(Socket socket) throws IOException {
            SSLSocketFactory sf = ((SSLSocketFactory) SSLSocketFactory.getDefault());
            InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
            SSLSocket s = (SSLSocket) (sf.createSocket(socket, remoteAddress.getHostName(),
                    socket.getPort(), true));

            // we are a server
            s.setUseClientMode(false);

            // allow all supported cipher suites
            s.setEnabledCipherSuites(s.getSupportedCipherSuites());

            return s;
        }
    };

    /** @return the host name that will be reported to SMTP clients */
    public String getHostName() {
        return this.hostName;
    }

    /** null means all interfaces */
    public Optional<InetAddress> getBindAddress() {
        return this.bindAddress;
    }

    /** */
    public int getPort() {
        return this.port;
    }

    public int getPortAllocated() {
        return this.allocatedPort;
    }

    /**
     * The string reported to the public as the software running here. Defaults
     * to SubEthaSTP and the version number.
     */
    public String getSoftwareName() {
        return this.softwareName;
    }

    /**
     * @return the ExecutorService handling client connections
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Is the server running after start() has been called?
     */
    public synchronized boolean isRunning() {
        return this.serverThread != null;
    }

    /**
     * The backlog is the Socket backlog.
     *
     * The backlog argument must be a positive value greater than 0. If the
     * value passed if equal or less than 0, then the default value will be
     * assumed.
     *
     * @return the backlog
     */
    public int getBacklog() {
        return this.backlog;
    }

    /**
     * Call this method to get things rolling after instantiating the
     * SMTPServer.
     * <p>
     * An SMTPServer which has been shut down, must not be reused.
     */
    public synchronized void start() {
        if (log.isInfoEnabled())
            log.info("SMTP server {} starting", getDisplayableLocalSocketAddress());

        if (this.started)
            throw new IllegalStateException("SMTPServer can only be started once. "
                    + "Restarting is not allowed even after a proper shutdown.");

        // Create our server socket here.
        ServerSocket serverSocket;
        try {
            serverSocket = this.createServerSocket();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        this.serverThread = new ServerThread(this, serverSocket);
        this.serverThread.start();
        this.started = true;
    }

    /**
     * Shut things down gracefully.
     */
    public synchronized void stop() {
        log.info("SMTP server {} stopping...", getDisplayableLocalSocketAddress());
        if (this.serverThread == null)
            return;

        this.serverThread.shutdown();
        this.serverThread = null;

        log.info("SMTP server {} stopped", getDisplayableLocalSocketAddress());
    }

    /**
     * Override this method if you want to create your own server sockets. You
     * must return a bound ServerSocket instance
     *
     * @throws IOException
     */
    protected ServerSocket createServerSocket() throws IOException {
        InetSocketAddress isa;

        if (!this.bindAddress.isPresent()) {
            isa = new InetSocketAddress(this.port);
        } else {
            isa = new InetSocketAddress(this.bindAddress.orElse(null), this.port);
        }

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(isa, this.backlog);

        if (this.port == 0) {
            this.allocatedPort = serverSocket.getLocalPort();
        }

        return serverSocket;
    }

    /**
     * Create a SSL socket that wraps the existing socket. This method is called
     * after the client issued the STARTTLS command.
     * <p>
     * Subclasses may override this method to configure the key stores, enabled
     * protocols/ cipher suites, enforce client authentication, etc.
     *
     * @param socket
     *            the existing socket as created by
     *            {@link #createServerSocket()} (not null)
     * @return a SSLSocket
     * @throws IOException
     *             when creating the socket failed
     */
    public final SSLSocket createSSLSocket(Socket socket) throws IOException {
        return sslSocketCreator.createSSLSocket(socket);
    }

    public String getDisplayableLocalSocketAddress() {
        return this.bindAddress.map(x -> x.toString()).orElse("*") + ":" + this.port;
    }

    /**
     * @return the factory for message handlers, cannot be null
     */
    public MessageHandlerFactory getMessageHandlerFactory() {
        return this.messageHandlerFactory;
    }

    /**
     * Returns the factor for authentication handling.
     * 
     * @return the factory for auth handlers, or empty if no factory has been
     *         set.
     */
    public Optional<AuthenticationHandlerFactory> getAuthenticationHandlerFactory() {
        return this.authenticationHandlerFactory;
    }

    /**
     * The CommandHandler manages handling the SMTP commands such as QUIT, MAIL,
     * RCPT, DATA, etc.
     *
     * @return An instance of CommandHandler
     */
    public CommandHandler getCommandHandler() {
        return this.commandHandler;
    }

    /** */
    public int getMaxConnections() {
        return this.maxConnections;
    }

    /** */
    public int getConnectionTimeout() {
        return this.connectionTimeout;
    }

    public int getMaxRecipients() {
        return this.maxRecipients;
    }

    /** */
    public boolean getEnableTLS() {
        return enableTLS;
    }

    /** */
    public boolean getHideTLS() {
        return this.hideTLS;
    }

    /** */
    public boolean getRequireTLS() {
        return this.requireTLS;
    }

    /** */
    public boolean getRequireAuth() {
        return requireAuth;
    }

    /**
     * @return the maxMessageSize
     */
    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    /** */
    public boolean getDisableReceivedHeaders() {
        return disableReceivedHeaders;
    }

    /** */
    public SessionIdFactory getSessionIdFactory() {
        return sessionIdFactory;
    }

    public static Builder port(int port) {
        return new Builder().port(port);
    }

}
