package org.frontcache.hystrix;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.frontcache.wrapper.HttpResponseWrapperImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

public class FC_ThroughCache_WebFilter extends HystrixCommand<WebResponse> {


	String url = "nothing";
	private final RequestContext context;
	private HttpClient client;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_WebFilter.class);
	
    public FC_ThroughCache_WebFilter(RequestContext context, HttpClient client) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Origin-Hits"))
        		);
        
        this.context = context;
        this.client = client;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpServletRequest httpRequest = context.getRequest();
		url = FCUtils.getRequestURL(httpRequest);
		HttpServletResponse httpResponse = context.getResponse();
		FilterChain chain = context.getFilterChain();

		FrontCacheHttpResponseWrapper wrappedResponse = new HttpResponseWrapperImpl(httpResponse);
		
		try {
			chain.doFilter(httpRequest, wrappedResponse); // run request to origin
			
			WebResponse webResponse = FCUtils.httpResponse2WebComponent(url, wrappedResponse);
			return webResponse;
			
		} catch (IOException | ServletException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new FrontCacheException("FilterChain exception", e);
		} 
		
    }
    
    @Override
    protected WebResponse getFallback() {
		context.setHystrixError();
		logger.error("FC-Origin-Hits-Filter - ORIGIN ERROR - " + url);
		WebResponse webResponse = FallbackResolverFactory.getInstance(client).getFallback(url);
		
		return webResponse;
    }
    
	
}