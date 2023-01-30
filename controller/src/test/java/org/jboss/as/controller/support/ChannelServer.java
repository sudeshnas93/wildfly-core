/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.controller.support;

import static org.wildfly.common.Assert.checkNotNullParam;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import org.jboss.remoting3.Channel;
import org.jboss.remoting3.Endpoint;
import org.jboss.remoting3.OpenListener;
import org.jboss.remoting3.Registration;
import org.jboss.remoting3.ServiceRegistrationException;
import org.jboss.remoting3.spi.NetworkServerProvider;
import org.wildfly.security.auth.realm.SimpleMapBackedSecurityRealm;
import org.wildfly.security.auth.server.MechanismConfiguration;
import org.wildfly.security.auth.server.sasl.SaslAuthenticationFactory;
import org.wildfly.security.auth.server.SecurityDomain;
import org.wildfly.security.password.interfaces.ClearPassword;
import org.wildfly.security.permission.PermissionVerifier;
import org.wildfly.security.sasl.util.SaslFactories;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.StreamConnection;
import org.xnio.channels.AcceptingChannel;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public class ChannelServer implements Closeable {

    private static boolean firstCreate = true;

    private final Endpoint endpoint;
    private Registration registration;
    private final AcceptingChannel<StreamConnection> streamServer;

    private ChannelServer(final Endpoint endpoint,
        final AcceptingChannel<StreamConnection> streamServer) {
        this.endpoint = endpoint;
        this.streamServer = streamServer;
    }

    public static ChannelServer create(final Configuration configuration) throws IOException {
        checkNotNullParam("configuration", configuration);
        configuration.validate();

        // Hack WFCORE-3302/REM3-303 workaround
        if (firstCreate) {
            firstCreate = false;
        } else {
            try {
                // wait in case the previous socket has not closed
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        // TODO WFCORE-3302 -- Endpoint.getCurrent() should be ok
        final Endpoint endpoint = Endpoint.builder().setEndpointName(configuration.getEndpointName()).build();

        final NetworkServerProvider networkServerProvider = endpoint.getConnectionProviderInterface(configuration.getUriScheme(), NetworkServerProvider.class);
        final SecurityDomain.Builder domainBuilder = SecurityDomain.builder();
        final SimpleMapBackedSecurityRealm realm = new SimpleMapBackedSecurityRealm();
        realm.setPasswordMap("bob", ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, "pass".toCharArray()));
        domainBuilder.addRealm("default", realm).build();
        domainBuilder.setDefaultRealmName("default");
        domainBuilder.setPermissionMapper((permissionMappable, roles) -> PermissionVerifier.ALL);
        SecurityDomain testDomain = domainBuilder.build();
        SaslAuthenticationFactory saslAuthenticationFactory = SaslAuthenticationFactory.builder()
            .setSecurityDomain(testDomain)
            .setMechanismConfigurationSelector(mechanismInformation -> {
                switch (mechanismInformation.getMechanismName()) {
                    case "ANONYMOUS":
                    case "PLAIN": {
                        return MechanismConfiguration.EMPTY;
                    }
                    default: return null;
                }
            })
            .setFactory(SaslFactories.getElytronSaslServerFactory())
            .build();
        AcceptingChannel<StreamConnection> streamServer = networkServerProvider.createServer(configuration.getBindAddress(), OptionMap.EMPTY, saslAuthenticationFactory, null);

        return new ChannelServer(endpoint, streamServer);
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void addChannelOpenListener(final String channelName, final OpenListener openListener) throws ServiceRegistrationException {
        registration = endpoint.registerService(channelName, new OpenListener() {
            public void channelOpened(final Channel channel) {
                if (openListener != null) {
                    openListener.channelOpened(channel);
                }
            }

            public void registrationTerminated() {
                if (openListener != null) {
                    openListener.registrationTerminated();
                }
            }
        }, OptionMap.EMPTY);

    }

    public void close() {
        IoUtils.safeClose(streamServer);
        IoUtils.safeClose(registration);
        // TODO WFCORE-3302 -- should not be necessary to dispose of endpoint
        if (endpoint != null) {
            endpoint.closeAsync();
            try {
                endpoint.awaitClosed();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    public static final class Configuration {
        private String endpointName;
        private String uriScheme;
        private InetSocketAddress bindAddress;
        private Executor executor;

        public Configuration() {
        }

        void validate() {
            checkNotNullParam("endpointName", endpointName);
            checkNotNullParam("uriScheme", uriScheme);
            checkNotNullParam("bindAddress", bindAddress);
        }

        public void setEndpointName(String endpointName) {
            this.endpointName = endpointName;
        }

        public String getEndpointName() {
            return endpointName;
        }

        public String getUriScheme() {
            return uriScheme;
        }

        public void setUriScheme(String uriScheme) {
            this.uriScheme = uriScheme;
        }

        public InetSocketAddress getBindAddress() {
            return bindAddress;
        }

        public void setBindAddress(final InetSocketAddress bindAddress) {
            this.bindAddress = bindAddress;
        }

        public Executor getExecutor() {
            return executor;
        }

        public void setExecutor(final Executor readExecutor) {
            this.executor = readExecutor;
        }
    }

}
