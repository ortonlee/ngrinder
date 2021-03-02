/*
 * Copyright (c) 2012-present NAVER Corp.
 *
 * This file is part of The nGrinder software distribution. Refer to
 * the file LICENSE which is part of The nGrinder distribution for
 * licensing details. The nGrinder distribution is available on the
 * Internet at https://naver.github.io/ngrinder
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.http;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.function.Supplier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.HttpProcessors;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.impl.nio.ClientHttp1StreamDuplexerFactory;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.RequestHandlerRegistry;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.impl.H2Processors;
import org.apache.hc.core5.http2.impl.nio.ClientH2StreamMultiplexerFactory;
import org.apache.hc.core5.http2.impl.nio.ClientHttpProtocolNegotiatorFactory;
import org.apache.hc.core5.http2.nio.support.DefaultAsyncPushConsumerFactory;
import org.apache.hc.core5.http2.ssl.ConscryptClientTlsStrategy;
import org.apache.hc.core5.http2.ssl.H2ClientTlsStrategy;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.conscrypt.Conscrypt;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Future;

public class HTTPRequester extends HttpAsyncRequester {

	private final SimpleConnPool<HttpHost, IOSession> connPool;

	private HttpVersionPolicy versionPolicy = HttpVersionPolicy.NEGOTIATE;

	public HTTPRequester(SimpleConnPool<HttpHost, IOSession> connPool) {
		super(IOReactorConfig.DEFAULT, ioEventHandlerFactory(), null, null, null, connPool);
		this.connPool = connPool;
	}

	private static IOEventHandlerFactory ioEventHandlerFactory() {
		final RequestHandlerRegistry<Supplier<AsyncPushConsumer>> registry = new RequestHandlerRegistry<>();
		final ClientHttp1StreamDuplexerFactory http1StreamHandlerFactory = new ClientHttp1StreamDuplexerFactory(
			HttpProcessors.client(),
			Http1Config.DEFAULT,
			CharCodingConfig.DEFAULT,
			null);
		final ClientH2StreamMultiplexerFactory http2StreamHandlerFactory = new ClientH2StreamMultiplexerFactory(
			H2Processors.client(),
			new DefaultAsyncPushConsumerFactory(registry),
			H2Config.DEFAULT,
			CharCodingConfig.DEFAULT,
			null);
		return new ClientHttpProtocolNegotiatorFactory(
			http1StreamHandlerFactory,
			http2StreamHandlerFactory,
			HttpVersionPolicy.NEGOTIATE,
			createConscryptTlsStrategy(),
			null);
	}

	private static TlsStrategy createConscryptTlsStrategy() {
		try {
			SSLContext sslContext = SSLContexts.custom()
				.setProvider(Conscrypt.newProvider())
				.build();
			return new ConscryptClientTlsStrategy(sslContext);
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			return new H2ClientTlsStrategy();
		}
	}

	@Override
	protected Future<AsyncClientEndpoint> doConnect(HttpHost host, Timeout timeout, Object attachment, FutureCallback<AsyncClientEndpoint> callback) {
		return super.doConnect(host, timeout, attachment != null ? attachment : versionPolicy, callback);
	}

	public void setVersionPolicy(HttpVersionPolicy versionPolicy) {
		this.versionPolicy = versionPolicy;
	}

	public SimpleConnPool<HttpHost, IOSession> getConnPool() {
		return connPool;
	}
}
