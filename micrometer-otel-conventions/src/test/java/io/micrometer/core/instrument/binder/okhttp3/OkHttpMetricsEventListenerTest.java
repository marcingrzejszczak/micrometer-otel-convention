/*
 * Copyright 2017 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.binder.okhttp3;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.observation.TimerObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.http.convention.OpenTelemetryHttpClientSemanticNameProvider;
import io.micrometer.observation.transport.http.tags.convention.OpenTelemetryHttpClientKeyValuesConvention;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import ru.lanwen.wiremock.ext.WiremockResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests for {@link OkHttpMetricsEventListener}.
 *
 * @author Bjarte S. Karlsen
 * @author Jon Schneider
 * @author Johnny Lim
 * @author Nurettin Yilmaz
 */
@ExtendWith(WiremockResolver.class)
class OkHttpMetricsEventListenerTest {

	private static final String URI_EXAMPLE_VALUE = "uriExample";

	private static final Function<Request, String> URI_MAPPER = req -> URI_EXAMPLE_VALUE;

	private MeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, new MockClock());

	private OkHttpClient client = new OkHttpClient.Builder().eventListener(defaultListenerBuilder().build()).build();

	private OkHttpMetricsEventListener.Builder defaultListenerBuilder() {
		return OkHttpMetricsEventListener.builder(registry, "okhttp.requests").tags(Tags.of("foo", "bar"))
				.uriMapper(URI_MAPPER);
	}

	@Test
	void timeSuccessfulWithLegacyObservation(@WiremockResolver.Wiremock WireMockServer server) throws IOException {
		ObservationRegistry observationRegistry = ObservationRegistry.create();
		TestHandler testHandler = new TestHandler();
		observationRegistry.observationConfig()
				.namingConfiguration(ObservationRegistry.ObservationNamingConfiguration.DEFAULT);
		OpenTelemetryHttpClientKeyValuesConvention openTelemetryHttpClientKeyValuesConvention = new OpenTelemetryHttpClientKeyValuesConvention();
		observationRegistry.observationConfig().observationHandler(testHandler);
		observationRegistry.observationConfig().observationHandler(new TimerObservationHandler(registry));
		client = new OkHttpClient.Builder()
				.eventListener(defaultListenerBuilder().observationRegistry(observationRegistry)
						.httpClientKeyValuesConvention(openTelemetryHttpClientKeyValuesConvention).build())
				.build();
		server.stubFor(any(anyUrl()));
		Request request = new Request.Builder().url(server.baseUrl()).build();

		client.newCall(request).execute().close();

		assertThat(registry.get("okhttp.requests")
				.tags("foo", "bar", "status", "200", "uri", URI_EXAMPLE_VALUE, "target.host", "localhost",
						"target.port", String.valueOf(server.port()), "target.scheme", "http")
				.timer().count()).isEqualTo(1L);
		assertThat(testHandler.context).isNotNull();
		assertThat(testHandler.context.getAllKeyValues()).contains(KeyValue.of("foo", "bar"),
				KeyValue.of("status", "200"));
	}

	@Test
	void timeSuccessfulWithStandardizedObservation(@WiremockResolver.Wiremock WireMockServer server)
			throws IOException {
		ObservationRegistry observationRegistry = ObservationRegistry.create();
		ObservationRegistry.ObservationNamingConfiguration configuration = ObservationRegistry.ObservationNamingConfiguration.STANDARDIZED;
		observationRegistry.observationConfig().namingConfiguration(configuration);
		OpenTelemetryHttpClientKeyValuesConvention openTelemetryHttpClientKeyValuesConvention = new OpenTelemetryHttpClientKeyValuesConvention();
		observationRegistry.observationConfig()
				.semanticNameProvider(new OpenTelemetryHttpClientSemanticNameProvider(configuration));
		observationRegistry.observationConfig().observationHandler(new TimerObservationHandler(registry));
		client = new OkHttpClient.Builder()
				.eventListener(defaultListenerBuilder().observationRegistry(observationRegistry)
						.httpClientKeyValuesConvention(openTelemetryHttpClientKeyValuesConvention).build())
				.build();
		server.stubFor(any(anyUrl()));
		Request request = new Request.Builder().url(server.baseUrl()).build();

		client.newCall(request).execute().close();

		// TODO: Obviously all of these can't be low cardinality keys
		assertThat(registry.get("http.client.duration")
				.tagKeys("http.flavor", "http.host", "http.method", "http.request_content_length",
						"http.response_content_length", "http.scheme", "http.status_code", "http.target", "http.url",
						"http.user_agent", "net.peer.ip", "net.peer.name", "net.peer.port")
				.timer().count()).isEqualTo(1L);
	}

	private void testRequestTags(@WiremockResolver.Wiremock WireMockServer server, Request request) throws IOException {
		server.stubFor(any(anyUrl()));
		OkHttpClient client = new OkHttpClient.Builder()
				.eventListener(OkHttpMetricsEventListener.builder(registry, "okhttp.requests")
						.uriMapper(req -> req.url().encodedPath()).tags(Tags.of("foo", "bar")).build())
				.build();

		client.newCall(request).execute().close();

		assertThat(registry.get("okhttp.requests")
				.tags("foo", "bar", "uri", "/helloworld.txt", "status", "200", "requestTag1", "tagValue1",
						"target.host", "localhost", "target.port", String.valueOf(server.port()), "target.scheme",
						"http")
				.timer().count()).isEqualTo(1L);
	}

	static class TestHandler implements ObservationHandler<Observation.Context> {

		Observation.Context context;

		@Override
		public void onStart(Observation.Context context) {
			this.context = context;
		}

		@Override
		public boolean supportsContext(Observation.Context context) {
			return true;
		}

	}

}
