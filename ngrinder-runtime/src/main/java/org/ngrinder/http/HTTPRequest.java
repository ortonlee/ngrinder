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

import net.grinder.plugin.http.HTTPPlugin;
import net.grinder.script.InvalidContextException;
import net.grinder.script.NoSuchStatisticException;
import net.grinder.script.Statistics;
import net.grinder.statistics.StatisticsIndexMap;
import org.apache.hc.client5.http.cookie.*;
import org.apache.hc.client5.http.impl.cookie.RFC6265StrictSpec;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncRequestBuilder;
import org.apache.hc.core5.http.nio.support.BasicResponseConsumer;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.util.Timeout;
import org.ngrinder.http.cookie.ThreadContextCookieStore;
import org.ngrinder.http.method.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.ngrinder.http.util.ContentTypeUtils.getContentType;

public class HTTPRequest implements HTTPHead, HTTPGet, HTTPPost, HTTPPut, HTTPPatch, HTTPDelete {

	private static final Logger LOGGER = LoggerFactory.getLogger(HTTPRequest.class);

	private static final CookieStore cookieStore = ThreadContextCookieStore.INSTANCE;
	private static final CookieSpec COOKIE_SPEC = new RFC6265StrictSpec();

	private final HTTPRequester requester;

	private CookieOrigin cookieOrigin;

	static {
		// noinspection ResultOfMethodCallIgnored
		HTTPPlugin.getPlugin();    // Ensure plugin is loaded
	}

	private HTTPRequest() {
		requester = new HTTPRequester();
		requester.start();
	}

	public static HTTPRequest create() {
		return new HTTPRequest();
	}

	@Override
	public HTTPResponse HEAD(String uri, List<NameValuePair> params, List<Header> headers) {
		return doRequest(uri, createRequest("HEAD", uri, params, headers));
	}

	@Override
	public HTTPResponse GET(String uri, List<NameValuePair> params, List<Header> headers) {
		return doRequest(uri, createRequest("GET", uri, params, headers));
	}

	@Override
	public HTTPResponse POST(String uri, byte[] content, List<Header> headers) {
		return doRequest(uri, createRequestWithBody("POST", uri, content, headers));
	}

	@Override
	public HTTPResponse POST(String uri, List<NameValuePair> params, List<Header> headers) {
		return doRequest(uri, createRequest("POST", uri, params, headers));
	}

	@Override
	public HTTPResponse PUT(String uri, byte[] content, List<Header> headers) {
		return doRequest(uri, createRequestWithBody("PUT", uri, content, headers));
	}

	@Override
	public HTTPResponse PUT(String uri, List<NameValuePair> params, List<Header> headers) {
		return doRequest(uri, createRequest("PUT", uri, params, headers));
	}

	@Override
	public HTTPResponse PATCH(String uri, byte[] content, List<Header> headers) {
		return doRequest(uri, createRequestWithBody("PATCH", uri, content, headers));
	}

	@Override
	public HTTPResponse PATCH(String uri, List<NameValuePair> params, List<Header> headers) {
		return doRequest(uri, createRequest("PATCH", uri, params, headers));
	}

	@Override
	public HTTPResponse DELETE(String uri, List<NameValuePair> params, List<Header> headers) {
		return doRequest(uri, createRequest("DELETE", uri, params, headers));
	}

	private HTTPResponse doRequest(String uri, AsyncRequestProducer producer) {
		try {
			AsyncClientEndpoint endpoint = getEndpoint(uri);

			Future<Message<HttpResponse, byte[]>> messageFuture = endpoint.execute(
				producer,
				new BasicResponseConsumer<>(new BasicAsyncEntityConsumer()),
				new SimpleFutureCallback<>(endpoint));
			Message<HttpResponse, byte[]> message = messageFuture.get();

			processResponseCookies(message.getHead().headerIterator("Set-Cookie"));

			aggregate(message);
			summarize(uri, message);

			return HTTPResponse.of(message);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private AsyncClientEndpoint getEndpoint(String uri) {
		try {
			final HttpHost httpHost = HttpHost.create(URI.create(uri));
			final Timeout connectionTimeout = Timeout.ofMilliseconds(HTTPRequestControl.getConnectionTimeout());

			long start = System.currentTimeMillis();
			AsyncClientEndpoint endpoint = requester.connect(httpHost, connectionTimeout).get();
			long end = System.currentTimeMillis();

			Statistics statistics = getStatistics();
			if (statistics.isTestInProgress()) {
				Statistics.StatisticsForTest statisticsForTest = statistics.getForCurrentTest();

				statisticsForTest.setLong(
					StatisticsIndexMap.HTTP_PLUGIN_CONNECT_TIME_KEY, end - start);
			}

			return endpoint;
		} catch (InterruptedException | ExecutionException | InvalidContextException | NoSuchStatisticException e) {
			throw new RuntimeException(e);
		}
	}

	private Statistics getStatistics() {
		return HTTPPlugin.getPlugin()
			.getPluginProcessContext()
			.getScriptContext()
			.getStatistics();
	}

	private void aggregate(Message<HttpResponse, byte[]> message) {
		Statistics statistics = getStatistics();

		if (!statistics.isTestInProgress()) {
			return;
		}

		try {
			Statistics.StatisticsForTest statisticsForTest = statistics.getForCurrentTest();

			statisticsForTest.setLong(
				StatisticsIndexMap.HTTP_PLUGIN_RESPONSE_STATUS_KEY, message.getHead().getCode());

			if (message.getHead().getCode() >= HttpURLConnection.HTTP_BAD_REQUEST) {
				statisticsForTest.addLong(
					StatisticsIndexMap.HTTP_PLUGIN_RESPONSE_ERRORS_KEY, 1);
			}

			statisticsForTest.addLong(
				StatisticsIndexMap.HTTP_PLUGIN_RESPONSE_LENGTH_KEY, message.getBody() == null ? 0 : message.getBody().length);

			statisticsForTest.addLong(
				StatisticsIndexMap.HTTP_PLUGIN_FIRST_BYTE_TIME_KEY, TimeToFirstByteHolder.getTotal());
		} catch (Exception e) {
			LOGGER.error("Fail to aggregate HTTP statistics", e);
		}
	}

	private void summarize(String uri, Message<HttpResponse, byte[]> message) {
		Logger logger = HTTPPlugin.getPlugin()
			.getPluginProcessContext()
			.getScriptContext()
			.getLogger();

		logger.info("{} -> {} {}, {} bytes",
			uri,
			message.getHead().getCode(),
			message.getHead().getReasonPhrase(),
			message.getBody() == null ? 0 : message.getBody().length);
	}

	private AsyncRequestProducer createRequest(String method, String uri, List<NameValuePair> params, List<Header> headers) {
		AsyncRequestBuilder builder = AsyncRequestBuilder
			.create(method)
			.setUri(uri);

		params.forEach(builder::addParameter);

		addRequestCookies(HttpHost.create(URI.create(uri)), headers).forEach(builder::addHeader);

		return builder.build();
	}

	private AsyncRequestProducer createRequestWithBody(String method, String uri, byte[] content, List<Header> headers) {
		AsyncRequestBuilder builder = AsyncRequestBuilder
			.create(method)
			.setUri(uri)
			.setEntity(content, getContentType(headers));

		addRequestCookies(HttpHost.create(URI.create(uri)), headers).forEach(builder::addHeader);

		return builder.build();
	}

	private List<Header> addRequestCookies(HttpHost httpHost, List<Header> headers) {
		cookieOrigin = new CookieOrigin(httpHost.getHostName(), 0, "/", false);

		final List<Cookie> cookies = cookieStore.getCookies();
		// Find cookies matching the given origin
		final List<Cookie> matchedCookies = new ArrayList<>();
		final Date now = new Date();
		boolean expired = false;
		for (final Cookie cookie : cookies) {
			if (!cookie.isExpired(now)) {
				if (COOKIE_SPEC.match(cookie, cookieOrigin)) {
					if (LOGGER.isDebugEnabled()) {
						LOGGER.debug("Cookie {} match {}", cookie, cookieOrigin);
					}
					matchedCookies.add(cookie);
				}
			} else {
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Cookie {} expired", cookie);
				}
				expired = true;
			}
		}
		// Per RFC 6265, 5.3
		// The user agent must evict all expired cookies if, at any time, an expired cookie
		// exists in the cookie store
		if (expired) {
			cookieStore.clearExpired(now);
		}
		// Generate Cookie request headers
		if (!matchedCookies.isEmpty()) {
			headers.addAll(COOKIE_SPEC.formatCookies(matchedCookies));
		}
		return headers;
	}

	private void processResponseCookies(Iterator<Header> iterator) {
		iterator.forEachRemaining(header -> {
			try {
				List<Cookie> cookies = COOKIE_SPEC.parse(header, cookieOrigin);
				for (Cookie cookie : cookies) {
					try {
						COOKIE_SPEC.validate(cookie, cookieOrigin);
						cookieStore.addCookie(cookie);
					} catch (MalformedCookieException e) {
						LOGGER.warn("Cookie rejected [{}] {}", cookie, e.getMessage());
					}
				}
			} catch (MalformedCookieException ex) {
				LOGGER.warn("Invalid cookie header: \"{}\". {}", header, ex.getMessage());
			}
		});
	}

	/**
	 * Set version policy one of FORCE_HTTP_1, FORCE_HTTP_2 and NEGOTIATE
	 */
	public void setVersionPolicy(HttpVersionPolicy versionPolicy) {
		requester.setVersionPolicy(versionPolicy);
	}
}
