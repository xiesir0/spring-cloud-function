/*
 * Copyright 2019-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.adapter.aws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.catalog.FunctionInspector;
import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.utils.FunctionClassUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 *
 * @author Oleg Zhurakousky
 * @since 3.1
 *
 *        see
 *        https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html#api-gateway-simple-proxy-for-lambda-output-format
 */
public class FunctionInvoker implements RequestStreamHandler {

	private static Log logger = LogFactory.getLog(FunctionInvoker.class);

	private ObjectMapper mapper;

	private FunctionInvocationWrapper function;

	public FunctionInvoker() {
		this.start();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
		Message requestMessage = this.generateMessage(input, context);

		Message<byte[]> responseMessage = (Message<byte[]>) this.function.apply(requestMessage);

		byte[] responseBytes = responseMessage.getPayload();
		if (requestMessage.getHeaders().containsKey("httpMethod") || requestMessage.getPayload() instanceof APIGatewayProxyRequestEvent) { // API Gateway
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("isBase64Encoded", false);

			MessageHeaders headers = responseMessage.getHeaders();
			int statusCode = headers.containsKey("statusCode")
					? (int) headers.get("statusCode")
					: 200;

			response.put("statusCode", statusCode);
			if (isKinesis(requestMessage)) {
				HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
				response.put("statusDescription", httpStatus.toString());
			}

			String body = new String(responseMessage.getPayload(), StandardCharsets.UTF_8).replaceAll("\"", "");
			response.put("body", body);

			Map<String, String> responseHeaders = new HashMap<>();
			headers.keySet().forEach(key -> responseHeaders.put(key, headers.get(key).toString()));

			response.put("headers", responseHeaders);
			responseBytes = mapper.writeValueAsBytes(response);
		}

		StreamUtils.copy(responseBytes, output);
	}

	private boolean isKinesis(Message<byte[]> requestMessage) {
		return requestMessage.getHeaders().containsKey("Records");
	}

	private void start() {
		ConfigurableApplicationContext context = SpringApplication.run(FunctionClassUtils.getStartClass());
		Environment environment = context.getEnvironment();
		String functionName = environment.getProperty("spring.cloud.function.definition");
		FunctionCatalog functionCatalog = context.getBean(FunctionCatalog.class);
		this.mapper = context.getBean(ObjectMapper.class);
		this.configureObjectMapper();

		if (logger.isInfoEnabled()) {
			logger.info("Locating function: '" + functionName + "'");
		}

		this.function = functionCatalog.lookup(functionName, "application/json");
		Assert.notNull(this.function, "Failed to lookup function " + functionName);

		if (!StringUtils.hasText(functionName)) {
			FunctionInspector inspector = context.getBean(FunctionInspector.class);
			functionName = inspector.getRegistration(this.function).getNames().toString();
		}

		if (logger.isInfoEnabled()) {
			logger.info("Located function: '" + functionName + "'");
		}
	}

	private void configureObjectMapper() {
		SimpleModule module = new SimpleModule();
		module.addDeserializer(Date.class, new JsonDeserializer<Date>() {
			@Override
			public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
					throws IOException {
				Calendar calendar = Calendar.getInstance();
				calendar.setTimeInMillis(jsonParser.getValueAsLong());
				return calendar.getTime();
			}
		});
		mapper.registerModule(module);
		mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Message<byte[]> generateMessage(InputStream input, Context context) throws IOException {
		byte[] payload = StreamUtils.copyToByteArray(input);

		if (logger.isInfoEnabled()) {
			logger.info("Incoming JSON for ApiGateway Event: " + new String(payload));
		}

		MessageBuilder messageBuilder = null;
		Object request = this.mapper.readValue(payload, Object.class);
		Type inputType = FunctionTypeUtils.getInputType(function.getFunctionType(), 0);
		if (FunctionTypeUtils.isMessage(inputType)) {
			inputType = FunctionTypeUtils.getImmediateGenericType(inputType, 0);
		}
		boolean mapInputType = (inputType instanceof ParameterizedType && ((Class<?>) ((ParameterizedType) inputType).getRawType()).isAssignableFrom(Map.class));
		if (request instanceof Map) {
			Map<String, ?> requestMap = (Map<String, ?>) request;
			if (requestMap.containsKey("Records")) {
				logger.info("Incoming request is Kinesis Event");
				Assert.isTrue(inputType instanceof Class && KinesisEvent.class.isAssignableFrom((Class<?>) inputType) || mapInputType,
						"Only KinesisEvent or Map type is supported as input type for functions that accept with Kinesis Event");
				Object event = mapInputType ? requestMap : this.mapper.convertValue(requestMap, KinesisEvent.class);
				messageBuilder = MessageBuilder.withPayload(event);
			}
			else if (requestMap.containsKey("httpMethod")) { // API Gateway
				logger.info("Incoming request is API Gateway");
				if (inputType.getTypeName().endsWith(APIGatewayProxyRequestEvent.class.getSimpleName())) {
					APIGatewayProxyRequestEvent gatewayEvent = this.mapper.convertValue(requestMap, APIGatewayProxyRequestEvent.class);
					messageBuilder = MessageBuilder.withPayload(gatewayEvent);
				}
				else if (mapInputType) {
					messageBuilder = MessageBuilder.withPayload(requestMap)
							.setHeader("httpMethod", requestMap.get("httpMethod"));
				}
				else {
					Object body = requestMap.remove("body");
					body = body instanceof String ? ("\"" + body + "\"").getBytes(StandardCharsets.UTF_8) : mapper.writeValueAsBytes(body);
					messageBuilder = MessageBuilder.withPayload(body)
							.copyHeaders(requestMap);
				}
			}
		}
		if (messageBuilder == null) {
			messageBuilder = MessageBuilder.withPayload(payload);
		}
		return messageBuilder.setHeader("aws-context", context).build();
	}
}
