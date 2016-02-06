package org.frontcache.edge.filter.route;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.HttpContext;
import org.frontcache.FCConfig;
import static org.frontcache.FrontCacheEngine.*;
import org.frontcache.core.FCHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
import org.springframework.cloud.netflix.zuul.filters.route.SimpleHostRoutingFilter.MySSLSocketFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.WebUtils;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.constants.ZuulConstants;
import com.netflix.zuul.constants.ZuulHeaders;
import com.netflix.zuul.context.RequestContext;

public class SimpleHostRoutingFilter extends ZuulFilter {

	private String keyStorePath = null;
	private String keyStorePassword = null;
	private String fcHostId = null;
	
    private static final Logger LOG = LoggerFactory.getLogger(SimpleHostRoutingFilter.class);

	
	private static final DynamicIntProperty SOCKET_TIMEOUT = DynamicPropertyFactory
			.getInstance().getIntProperty(ZuulConstants.ZUUL_HOST_SOCKET_TIMEOUT_MILLIS,
					10000);

	private static final DynamicIntProperty CONNECTION_TIMEOUT = DynamicPropertyFactory
			.getInstance().getIntProperty(ZuulConstants.ZUUL_HOST_CONNECT_TIMEOUT_MILLIS,
					10000);

	private final Timer connectionManagerTimer = new Timer(
			"SimpleHostRoutingFilter.connectionManagerTimer", true);

	private ProxyRequestHelper helper;
	private PoolingHttpClientConnectionManager connectionManager;
	private CloseableHttpClient httpClient;

	private final Runnable clientloader = new Runnable() {
		@Override
		public void run() {
			try {
				httpClient.close();
			} catch (IOException ex) {
				LOG.error("error closing client", ex);
			}
			httpClient = newClient();
		}
	};

	public SimpleHostRoutingFilter() {
		this(new ProxyRequestHelper());
		fcHostId = FCConfig.getProperty("front-cache.host-name");
		if (null == fcHostId)
			fcHostId = DEFAULT_FRONTCACHE_HOST_NAME_VALUE;
		
		keyStorePath = FCConfig.getProperty("front-cache.keystore-path");
		keyStorePassword = FCConfig.getProperty("front-cache.keystore-password");
	}

	public SimpleHostRoutingFilter(ProxyRequestHelper helper) {
		this.helper = helper;
	}

	@PostConstruct
	private void initialize() {
		this.httpClient = newClient();
		SOCKET_TIMEOUT.addCallback(clientloader);
		CONNECTION_TIMEOUT.addCallback(clientloader);
		connectionManagerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (connectionManager == null) {
					return;
				}
				connectionManager.closeExpiredConnections();
			}
		}, 30000, 5000);
	}

	@PreDestroy
	public void stop() {
		connectionManagerTimer.cancel();
	}

	@Override
	public String filterType() {
		return "route";
	}

	@Override
	public int filterOrder() {
		return 100;
	}

	@Override
	public boolean shouldFilter() {
		return RequestContext.getCurrentContext().getRouteHost() != null
				&& RequestContext.getCurrentContext().sendZuulResponse();
	}
	
	private String buildZuulRequestURI(HttpServletRequest request) {
		RequestContext context = RequestContext.getCurrentContext();
		String uri = request.getRequestURI();
		String contextURI = (String) context.get("requestURI");
		if (contextURI != null) {
			try {
				uri = UriUtils.encodePath(contextURI,
						WebUtils.DEFAULT_CHARACTER_ENCODING);
			}
			catch (Exception e) {
				LOG.debug(
						"unable to encode uri path from context, falling back to uri from request",
						e);
			}
		}
		return uri;
	}



	@Override
	public Object run() {
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletRequest request = context.getRequest();
		MultiValueMap<String, String> headers = this.helper
				.buildZuulRequestHeaders(request);
		MultiValueMap<String, String> params = this.helper
				.buildZuulRequestQueryParams(request);
		String verb = getVerb(request);
		InputStream requestEntity = getRequestBody(request);

		String uri = buildZuulRequestURI(request);

		try {
			HttpResponse response = forward(httpClient, verb, uri, request, headers,
					params, requestEntity);
			setResponse(response);
			HttpServletResponse servletResponse = context.getResponse();
			servletResponse.addHeader(FCHeaders.X_FRONTCACHE_HOST, fcHostId);
		}
		catch (Exception ex) {
			context.set("error.status_code", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			context.set("error.exception", ex);
		}
		return null;
	}

	protected PoolingHttpClientConnectionManager newConnectionManager() {
		try {
	        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
	        trustStore.load(new FileInputStream(keyStorePath), keyStorePassword.toCharArray());

	        MySSLSocketFactory sf = new MySSLSocketFactory(trustStore);
	        sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
			

			final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
					.register("http", PlainConnectionSocketFactory.INSTANCE)
					//.register("https", new SSLConnectionSocketFactory(sslContext))
					.register("https", sf)
					.build();

			connectionManager = new PoolingHttpClientConnectionManager(registry);
			connectionManager.setMaxTotal(Integer.parseInt(System.getProperty("zuul.max.host.connections", "200")));
			connectionManager.setDefaultMaxPerRoute(Integer.parseInt(System.getProperty("zuul.max.host.connections", "20")));
			return connectionManager;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	protected CloseableHttpClient newClient() {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setSocketTimeout(SOCKET_TIMEOUT.get())
				.setConnectTimeout(CONNECTION_TIMEOUT.get())
				.setCookieSpec(CookieSpecs.IGNORE_COOKIES)
				.build();

		return HttpClients.custom()
				.setConnectionManager(newConnectionManager())
				.setDefaultRequestConfig(requestConfig)
				.setRetryHandler(new DefaultHttpRequestRetryHandler(0, false))
				.setRedirectStrategy(new RedirectStrategy() {
					@Override
					public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return false;
					}

					@Override
					public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return null;
					}
				})
				.build();
	}

	private HttpResponse forward(HttpClient httpclient, String verb, String uri,
								 HttpServletRequest request, MultiValueMap<String, String> headers,
								 MultiValueMap<String, String> params, InputStream requestEntity)
			throws Exception {
		Map<String, Object> info = this.helper.debug(verb, uri, headers, params,
				requestEntity);
		URL host = RequestContext.getCurrentContext().getRouteHost();
		HttpHost httpHost = getHttpHost(host);
		uri = StringUtils.cleanPath((host.getPath() + uri).replaceAll("/{2,}", "/"));
		HttpRequest httpRequest;
		switch (verb.toUpperCase()) {
		case "POST":
			HttpPost httpPost = new HttpPost(uri + getQueryString());
			httpRequest = httpPost;
			httpPost.setEntity(new InputStreamEntity(requestEntity, request
					.getContentLength()));
			break;
		case "PUT":
			HttpPut httpPut = new HttpPut(uri + getQueryString());
			httpRequest = httpPut;
			httpPut.setEntity(new InputStreamEntity(requestEntity, request
					.getContentLength()));
			break;
		case "PATCH":
			HttpPatch httpPatch = new HttpPatch(uri + getQueryString());
			httpRequest = httpPatch;
			httpPatch.setEntity(new InputStreamEntity(requestEntity, request
					.getContentLength()));
			break;
		default:
			httpRequest = new BasicHttpRequest(verb, uri + getQueryString());
			LOG.debug(uri + getQueryString());
		}
		try {
			
			httpRequest.setHeaders(convertHeaders(headers));
			Header acceptEncoding = httpRequest.getFirstHeader(ZuulHeaders.ACCEPT_ENCODING);
			if (acceptEncoding != null && acceptEncoding.getValue().contains("gzip"))
			{
				httpRequest.setHeader(ZuulHeaders.ACCEPT_ENCODING, "gzip");
			}
			LOG.debug(httpHost.getHostName() + " " + httpHost.getPort() + " "
					+ httpHost.getSchemeName());
			HttpResponse zuulResponse = forwardRequest(httpclient, httpHost, httpRequest);
			this.helper.appendDebug(info, zuulResponse.getStatusLine().getStatusCode(),
					revertHeaders(zuulResponse.getAllHeaders()));

			return zuulResponse;
		}
		finally {
			// When HttpClient instance is no longer needed,
			// shut down the connection manager to ensure
			// immediate deallocation of all system resources
			// httpclient.getConnectionManager().shutdown();
		}
	}

	private MultiValueMap<String, String> revertHeaders(Header[] headers) {
		MultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>();
		for (Header header : headers) {
			String name = header.getName();
			if (!map.containsKey(name)) {
				map.put(name, new ArrayList<String>());
			}
			map.get(name).add(header.getValue());
		}
		return map;
	}

	private Header[] convertHeaders(MultiValueMap<String, String> headers) {
		List<Header> list = new ArrayList<>();
		for (String name : headers.keySet()) {
			for (String value : headers.get(name)) {
				list.add(new BasicHeader(name, value));
			}
		}
		return list.toArray(new BasicHeader[0]);
	}

	private HttpResponse forwardRequest(HttpClient httpclient, HttpHost httpHost,
			HttpRequest httpRequest) throws IOException {
		return httpclient.execute(httpHost, httpRequest);
	}

	private String getQueryString() throws UnsupportedEncodingException {
		HttpServletRequest request = RequestContext.getCurrentContext().getRequest();
		MultiValueMap<String, String> params=helper.buildZuulRequestQueryParams(request);
		StringBuilder query=new StringBuilder();
		for (Map.Entry<String, List<String>> entry : params.entrySet()) {
			String key=URLEncoder.encode(entry.getKey(), "UTF-8");
			for (String value : entry.getValue()) {
				query.append("&");
				query.append(key);
				query.append("=");
				query.append(URLEncoder.encode(value, "UTF-8"));
			}
		}
		return (query.length()>0) ? "?" + query.substring(1) : "";
	}

	private HttpHost getHttpHost(URL host) {
		HttpHost httpHost = new HttpHost(host.getHost(), host.getPort(),
				host.getProtocol());
		return httpHost;
	}

	private InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		try {
			requestEntity = request.getInputStream();
		}
		catch (IOException ex) {
			// no requestBody is ok.
		}
		return requestEntity;
	}

	private String getVerb(HttpServletRequest request) {
		String sMethod = request.getMethod();
		return sMethod.toUpperCase();
	}

	private void setResponse(HttpResponse response) throws IOException {
		
		this.helper.setResponse(response.getStatusLine().getStatusCode(),
				response.getEntity() == null ? null : response.getEntity().getContent(),
				revertHeaders(response.getAllHeaders()));
	}

	/**
	 * Add header names to exclude from proxied response in the current request.
	 * @param names
	 */
	protected void addIgnoredHeaders(String... names) {
		helper.addIgnoredHeaders(names);
	}

}